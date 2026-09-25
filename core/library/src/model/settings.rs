/// Какую версию играть, если их несколько (plan.md 2.2) — ответ на цензуру.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum VersionPreference {
    /// Оригинальная, explicit — умолчание.
    #[default]
    Original,
    /// Без мата: машина с детьми, работа.
    Clean,
    Any,
}

/// Настройки, которые едут за пользователем между устройствами (B1.4) и
/// потому живут в журнале. Всё, что привязано к устройству (папки, вывод
/// звука, стартовый экран), остаётся в DataStore приложения.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SyncedSettings {
    pub version_preference: VersionPreference,
}
