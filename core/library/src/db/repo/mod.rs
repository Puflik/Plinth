//! Репозитории (B2.2) — методы [`crate::db::Database`] по сущностям.
//! `save_*` вставляет или заменяет запись целиком; связки (артисты трека,
//! псевдонимы) заменяются в той же транзакции.

mod album_repo;
mod artist_repo;
mod blocklist_repo;
mod history_repo;
mod journal_repo;
mod merge_repo;
mod playlist_repo;
mod setting_repo;
mod source_repo;
mod subscription_repo;
mod track_repo;

#[cfg(test)]
pub(crate) mod fixtures;
