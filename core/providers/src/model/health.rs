use std::time::Duration;

use plinth_types::{CoreError, Timestamp};

use crate::http::http_status;

/// Здоровье провайдера (E1.2, plan.md 6.4). Его считает реестр по исходам
/// вызовов, сам провайдер о себе не докладывает: ошибка → `Degraded`,
/// [`HealthPolicy::down_after`] подряд → `Down` на [`HealthPolicy::down_for`].
/// Первый же успех возвращает `Ok`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Health {
    Ok,
    Degraded {
        failures: u32,
    },
    /// До `until` провайдера не спрашивают в поиске; потом — одна попытка.
    Down {
        failures: u32,
        until: Timestamp,
    },
}

/// Пороги здоровья; придут из конфига, когда провайдеров станет больше одного.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HealthPolicy {
    pub down_after: u32,
    pub down_for: Duration,
}

impl Default for HealthPolicy {
    fn default() -> Self {
        Self { down_after: 3, down_for: Duration::from_secs(5 * 60) }
    }
}

impl Health {
    /// Неудач подряд.
    pub fn failures(self) -> u32 {
        match self {
            Self::Ok => 0,
            Self::Degraded { failures } | Self::Down { failures, .. } => failures,
        }
    }

    pub fn is_down_at(self, now: Timestamp) -> bool {
        matches!(self, Self::Down { until, .. } if now < until)
    }

    pub fn after_success(self) -> Self {
        Self::Ok
    }

    pub fn after_failure(self, policy: HealthPolicy, now: Timestamp) -> Self {
        self.after_failure_for(policy, now, policy.down_for)
    }

    fn after_failure_for(self, policy: HealthPolicy, now: Timestamp, down_for: Duration) -> Self {
        let failures = self.failures().saturating_add(1);
        if failures >= policy.down_after {
            Self::Down { failures, until: now + down_for }
        } else {
            Self::Degraded { failures }
        }
    }

    /// Исход вызова для здоровья. `Unavailable` — провайдер ответил «такого
    /// нет»: он жив, пропал элемент. Ответ 4xx (кроме 429) — наш запрос не
    /// годится, провайдер ни при чём: здоровье не меняется. Нет соединения —
    /// может быть и у телефона, и у провайдера: счёт идёт, но бан короткий
    /// (`down_for` / 20), чтобы сеть, вернувшаяся после трёх набранных без неё
    /// запросов, не ждала пять минут. Остальное — 5xx, 429, непонятный ответ,
    /// ошибка в коде провайдера — его отказ.
    pub fn after(self, outcome: Result<(), &CoreError>, policy: HealthPolicy, now: Timestamp) -> Self {
        match outcome {
            Ok(()) | Err(CoreError::Unavailable { .. }) => self.after_success(),
            Err(error @ CoreError::Network { .. }) => match http_status(error) {
                Some(status) if status != 429 && (400..500).contains(&status) => self,
                Some(_) => self.after_failure(policy, now),
                None => self.after_failure_for(policy, now, policy.down_for / CONNECTION_HOLD_DIVISOR),
            },
            Err(_) => self.after_failure(policy, now),
        }
    }
}

/// Во сколько раз бан за «нет соединения» короче бана за отказ сервера.
const CONNECTION_HOLD_DIVISOR: u32 = 20;

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{CoreError, Timestamp};

    use super::{Health, HealthPolicy};

    const POLICY: HealthPolicy = HealthPolicy { down_after: 3, down_for: Duration::from_secs(60) };

    fn at(seconds: i64) -> Timestamp {
        Timestamp::from_millis(seconds * 1000)
    }

    #[test]
    fn failures_degrade_then_take_down() {
        let once = Health::Ok.after_failure(POLICY, at(0));
        let twice = once.after_failure(POLICY, at(1));
        let thrice = twice.after_failure(POLICY, at(2));

        assert_eq!(once, Health::Degraded { failures: 1 });
        assert_eq!(twice, Health::Degraded { failures: 2 });
        assert_eq!(thrice, Health::Down { failures: 3, until: at(62) });
    }

    #[test]
    fn down_lasts_until_the_deadline() {
        let down = Health::Down { failures: 3, until: at(62) };

        assert!(down.is_down_at(at(61)));
        assert!(!down.is_down_at(at(62)));
        assert!(!Health::Degraded { failures: 2 }.is_down_at(at(0)));
        assert!(!Health::Ok.is_down_at(at(0)));
    }

    #[test]
    fn failed_probe_after_down_goes_down_again() {
        let down = Health::Down { failures: 3, until: at(62) };

        assert_eq!(down.after_failure(POLICY, at(70)), Health::Down { failures: 4, until: at(130) });
    }

    #[test]
    fn success_heals_at_once() {
        assert_eq!(Health::Down { failures: 3, until: at(62) }.after_success(), Health::Ok);
        assert_eq!(Health::Degraded { failures: 2 }.after_success(), Health::Ok);
    }

    #[test]
    fn missing_item_is_not_the_providers_fault() {
        let degraded = Health::Degraded { failures: 2 };
        let gone = CoreError::unavailable("item is gone");

        assert_eq!(degraded.after(Err(&gone), POLICY, at(0)), Health::Ok);
        for failure in [
            CoreError::network("http 503"),
            CoreError::parse("html instead of json"),
            CoreError::internal("bug"),
        ] {
            assert_eq!(degraded.after(Err(&failure), POLICY, at(0)), Health::Down { failures: 3, until: at(60) });
        }
        assert_eq!(degraded.after(Ok(()), POLICY, at(0)), Health::Ok);
    }

    #[test]
    fn default_policy_is_three_failures_five_minutes() {
        assert_eq!(HealthPolicy::default(), HealthPolicy { down_after: 3, down_for: Duration::from_secs(300) });
    }

    #[test]
    fn a_rejected_request_is_not_the_providers_failure() {
        let mut health = Health::Ok;
        for _ in 0..5 {
            health = health.after(Err(&CoreError::network("http 414")), POLICY, at(0));
        }
        assert_eq!(health, Health::Ok);
    }

    #[test]
    fn no_connection_holds_the_provider_down_only_briefly() {
        let mut health = Health::Ok;
        for _ in 0..3 {
            health = health.after(Err(&CoreError::network("UnknownHostException")), POLICY, at(0));
        }
        assert_eq!(health, Health::Down { failures: 3, until: at(3) });
    }

    #[test]
    fn server_failures_hold_the_provider_down_for_the_full_time() {
        let mut health = Health::Ok;
        for status in ["http 503", "http 429", "http 500"] {
            health = health.after(Err(&CoreError::network(status)), POLICY, at(0));
        }
        assert_eq!(health, Health::Down { failures: 3, until: at(60) });
    }
}
