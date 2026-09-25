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

/// Одна настройка со значением — то, что меняет операция журнала (C2).
/// Настройки независимы: правка одной на телефоне и другой на планшете не
/// спорят между собой.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Setting {
    VersionPreference(VersionPreference),
}

impl SyncedSettings {
    /// Настройки с одной изменённой.
    pub fn with(self, setting: Setting) -> Self {
        match setting {
            Setting::VersionPreference(version_preference) => Self { version_preference },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Setting, SyncedSettings, VersionPreference};

    #[test]
    fn a_setting_changes_only_itself() {
        let settings = SyncedSettings::default();

        let changed = settings.with(Setting::VersionPreference(VersionPreference::Clean));

        assert_eq!(settings.version_preference, VersionPreference::Original);
        assert_eq!(changed.version_preference, VersionPreference::Clean);
    }
}
