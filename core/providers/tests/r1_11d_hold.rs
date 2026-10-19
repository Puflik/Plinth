//! Р1.11г (`docs/work/r1-11d.md`, находка v0.2 №30): сервер, который назвал срок
//! (`Retry-After`), получает паузу ровно в этот срок — с первого отказа, а не после
//! трёх. Раньше поиск стучался заново через секунды, а три отказа отправляли
//! провайдера на пять минут.
//!
//! `check_status` и `timed_out` — новый API (сигнатуры в `docs/work/r1-11d.md`):
//! пока их нет, тесты не собираются.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::sync::Arc;
use std::time::Duration;

use plinth_providers::http::{check_status, timed_out};
use plinth_providers::model::{Health, HealthPolicy, SearchQuery};
use plinth_providers::testing::FakeProvider;
use plinth_providers::{Provider, Registry};
use plinth_types::{CoreError, ProviderId, Timestamp};

const POLICY: HealthPolicy = HealthPolicy { down_after: 3, down_for: Duration::from_secs(60) };

fn at(seconds: i64) -> Timestamp {
    Timestamp::from_millis(seconds * 1000)
}

/// Отказ сервера с этим статусом; `wait` — сколько секунд он просил подождать.
fn refused(status: u16, wait: Option<u64>) -> CoreError {
    check_status(status, wait.map(Duration::from_secs)).unwrap_err()
}

#[test]
fn a_server_that_names_the_time_holds_the_provider_for_that_time() {
    for status in [429, 503] {
        let health = Health::Ok.after(Err(&refused(status, Some(30))), POLICY, at(100));

        assert_eq!(health, Health::Down { failures: 1, until: at(130) }, "{status}");
    }
}

#[test]
fn the_hold_never_outlasts_the_usual_ban() {
    let health = Health::Ok.after(Err(&refused(429, Some(3600))), POLICY, at(0));

    assert_eq!(health, Health::Down { failures: 1, until: at(60) });
}

#[test]
fn a_hold_adds_to_the_failures_already_counted() {
    let health = Health::Degraded { failures: 2 }.after(Err(&refused(429, Some(30))), POLICY, at(0));

    assert_eq!(health, Health::Down { failures: 3, until: at(30) });
}

#[test]
fn a_hold_that_has_passed_lets_the_provider_in_and_one_good_answer_heals_it() {
    let down = Health::Ok.after(Err(&refused(429, Some(30))), POLICY, at(0));

    assert!(down.is_down_at(at(29)));
    assert!(!down.is_down_at(at(30)));
    assert_eq!(down.after(Ok(()), POLICY, at(31)), Health::Ok);
}

#[test]
fn a_zero_wait_is_an_ordinary_failure() {
    let health = Health::Ok.after(Err(&refused(429, Some(0))), POLICY, at(0));

    assert_eq!(health, Health::Degraded { failures: 1 });
}

#[test]
fn without_a_named_time_the_old_rules_stand() {
    for status in [429, 500, 503] {
        let mut health = Health::Ok;
        for _ in 0..3 {
            health = health.after(Err(&refused(status, None)), POLICY, at(0));
        }

        assert_eq!(health, Health::Down { failures: 3, until: at(60) }, "{status}");
    }
}

#[test]
fn a_rejected_request_is_not_the_providers_failure_even_with_a_time() {
    for status in [400, 403, 414] {
        let health = Health::Ok.after(Err(&refused(status, Some(30))), POLICY, at(0));

        assert_eq!(health, Health::Ok, "{status}");
    }
}

#[test]
fn a_timeout_is_held_as_briefly_as_no_connection() {
    let mut health = Health::Ok;
    for _ in 0..3 {
        health = health.after(Err(&timed_out()), POLICY, at(0));
    }

    // `down_for / 20`.
    assert_eq!(health, Health::Down { failures: 3, until: at(3) });
}

#[test]
fn a_provider_that_asked_for_time_is_not_asked_before_it() {
    let fake = Arc::new(FakeProvider::sample("fake.test").unwrap());
    fake.fail_with(Some(refused(429, Some(30))));
    let registry = Registry::new(vec![fake.clone() as Arc<dyn Provider>], POLICY).unwrap();
    let query = SearchQuery::new("fake", 10);

    let first = registry.search(&query, at(0));
    let during = registry.search(&query, at(10));

    assert_eq!(fake.requests(), 1, "one failure was enough: the server named the time");
    assert!(matches!(first[0].outcome, Err(CoreError::Network { .. })), "{:?}", first[0].outcome);
    assert!(during[0].outcome.is_err());

    fake.fail_with(None);
    let after = registry.search(&query, at(30));

    assert_eq!(fake.requests(), 2);
    assert!(after[0].outcome.is_ok());
    assert_eq!(registry.health(&ProviderId::new("fake.test").unwrap()), Some(Health::Ok));
}
