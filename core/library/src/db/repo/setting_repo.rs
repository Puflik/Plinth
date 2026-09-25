use plinth_types::CoreError;
use rusqlite::params;

use crate::db::Database;
use crate::db::codes::Code;
use crate::db::sql::Storage;
use crate::model::{Setting, SyncedSettings, VersionPreference};

const VERSION_PREFERENCE: &str = "version_preference";

impl Database {
    pub fn save_setting(&self, setting: Setting) -> Result<(), CoreError> {
        let (key, value) = match setting {
            Setting::VersionPreference(preference) => (VERSION_PREFERENCE, preference.code()),
        };
        self.conn()
            .execute("INSERT OR REPLACE INTO setting(key, value) VALUES (?1, ?2)", params![key, value])
            .storage()
            .map(drop)
    }

    /// Синхронизируемые настройки; не заданные — по умолчанию. Незнакомые
    /// ключи и значения (из более новой версии) пропускаются.
    pub fn synced_settings(&self) -> Result<SyncedSettings, CoreError> {
        let mut statement = self.conn().prepare("SELECT key, value FROM setting").storage()?;
        let rows = statement
            .query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)))
            .storage()?
            .collect::<Result<Vec<_>, _>>()
            .storage()?;
        let mut settings = SyncedSettings::default();
        for (key, value) in rows {
            let setting = match key.as_str() {
                VERSION_PREFERENCE => VersionPreference::from_code(&value).map(Setting::VersionPreference),
                _ => None,
            };
            if let Some(setting) = setting {
                settings = settings.with(setting);
            }
        }
        Ok(settings)
    }
}

#[cfg(test)]
mod tests {
    use crate::db::repo::fixtures::db;
    use crate::model::{Setting, SyncedSettings, VersionPreference};

    #[test]
    fn unset_settings_are_defaults() {
        assert_eq!(db().synced_settings().unwrap(), SyncedSettings::default());
    }

    #[test]
    fn a_saved_setting_comes_back() {
        let db = db();

        db.save_setting(Setting::VersionPreference(VersionPreference::Clean)).unwrap();

        assert_eq!(db.synced_settings().unwrap().version_preference, VersionPreference::Clean);
    }
}
