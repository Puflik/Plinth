//! Миграции схемы (B3.1). Правила — ADR 0011: только вперёд, каждая в
//! своей транзакции, перед каждой — копия базы; выпущенная миграция
//! заморожена, изменение схемы — новая миграция в конце реестра.

mod m0001_initial;
mod m0002_scan_file;
mod m0003_sort_keys;
mod runner;

pub(crate) use runner::{MigrationError, migrate};

/// Шаг схемы: `apply` получает открытую транзакцию и переводит базу на
/// `version`. Может менять и данные (пересчёт нормализованных колонок).
#[derive(Clone, Copy)]
pub(crate) struct Migration {
    pub version: i32,
    pub name: &'static str,
    pub apply: fn(&rusqlite::Transaction<'_>) -> rusqlite::Result<()>,
}

/// Реестр по порядку; версии подряд с единицы.
pub(crate) const MIGRATIONS: &[Migration] =
    &[m0001_initial::MIGRATION, m0002_scan_file::MIGRATION, m0003_sort_keys::MIGRATION];

#[cfg(test)]
mod tests {
    use super::MIGRATIONS;

    /// Реестр: версии идут подряд с единицы — пропуск или повтор ломает порядок применения.
    #[test]
    fn registry_is_contiguous_from_one() {
        let versions: Vec<i32> = MIGRATIONS.iter().map(|m| m.version).collect();

        let latest = i32::try_from(MIGRATIONS.len()).unwrap();
        assert_eq!(versions, (1..=latest).collect::<Vec<_>>());
        assert!(MIGRATIONS.iter().all(|m| !m.name.is_empty()));
    }
}
