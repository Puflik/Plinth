//! Миграция 1 — начальная схема (B2.1, B3.1). **Заморожена:** после выпуска
//! её не правят; любое изменение схемы — новая миграция (ADR 0011).
//!
//! Две природы таблиц (plan.md 17.4, `model/mod.rs`):
//! - **каталог** (`artist`, `album`, `track`, `version`, `source` и связки)
//!   пересобирается сканом и провайдерами, связан внешними ключами;
//! - **проекция журнала** (`track_user`, `playlist*`, `play_event`,
//!   `merge_decision`, `subscription`, `block_entry`, `setting`) незаменима в
//!   журнале, а здесь — его отражение. На каталог она **не ссылается**
//!   внешними ключами: журнал с другого устройства может говорить о треке,
//!   которого в здешнем каталоге ещё нет.
//!
//! Идентификаторы — BLOB из 16 байт, время — мс от эпохи, перечисления —
//! текстовые коды (`db/codes.rs`). Таблицы `STRICT`: SQLite не подменит тип.

use super::Migration;

pub(crate) const MIGRATION: Migration = Migration { version: 1, name: "initial", apply };

fn apply(tx: &rusqlite::Transaction<'_>) -> rusqlite::Result<()> {
    tx.execute_batch(SCHEMA)?;
    tx.execute_batch(INDEXES)
}

const SCHEMA: &str = "
CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value INTEGER NOT NULL
) STRICT;

-- Каталог --------------------------------------------------------------

CREATE TABLE artist (
    id              BLOB PRIMARY KEY,
    name            TEXT NOT NULL,
    name_normalized TEXT NOT NULL,
    sort_name       TEXT,
    mbid            TEXT,
    bio             TEXT
) STRICT;

CREATE TABLE artist_alias (
    artist BLOB NOT NULL REFERENCES artist(id) ON DELETE CASCADE,
    alias  TEXT NOT NULL,
    PRIMARY KEY (artist, alias)
) STRICT;

CREATE TABLE album (
    id               BLOB PRIMARY KEY,
    title            TEXT NOT NULL,
    title_normalized TEXT NOT NULL,
    artist_credit    TEXT NOT NULL,
    year             INTEGER,
    label            TEXT,
    country          TEXT,
    disc_count       INTEGER,
    mbid_release     TEXT
) STRICT;

CREATE TABLE album_artist (
    album  BLOB NOT NULL REFERENCES album(id) ON DELETE CASCADE,
    artist BLOB NOT NULL REFERENCES artist(id) ON DELETE CASCADE,
    ord    INTEGER NOT NULL,
    PRIMARY KEY (album, artist)
) STRICT;

CREATE TABLE track (
    id                BLOB PRIMARY KEY,
    title             TEXT NOT NULL,
    title_normalized  TEXT NOT NULL,
    artist_credit     TEXT NOT NULL,
    artist_normalized TEXT NOT NULL,
    mbid_work         TEXT,
    added_at          INTEGER NOT NULL
) STRICT;

CREATE TABLE track_artist (
    track  BLOB NOT NULL REFERENCES track(id) ON DELETE CASCADE,
    artist BLOB NOT NULL REFERENCES artist(id) ON DELETE CASCADE,
    ord    INTEGER NOT NULL,
    PRIMARY KEY (track, artist)
) STRICT;

CREATE TABLE version (
    id             BLOB PRIMARY KEY,
    track          BLOB NOT NULL REFERENCES track(id) ON DELETE CASCADE,
    kind           TEXT NOT NULL CHECK (kind IN ('original', 'live', 'acoustic', 'remaster', 'radio_edit')),
    explicitness   TEXT NOT NULL CHECK (explicitness IN ('explicit', 'clean', 'unknown')),
    duration_ms    INTEGER CHECK (duration_ms >= 0),
    album          BLOB REFERENCES album(id) ON DELETE SET NULL,
    disc           INTEGER,
    number         INTEGER,
    release_year   INTEGER,
    mbid_recording TEXT,
    fingerprint    TEXT
) STRICT;

-- Источник — либо локальный файл, либо трек провайдера, не то и другое сразу.
CREATE TABLE source (
    id              BLOB PRIMARY KEY,
    version         BLOB NOT NULL REFERENCES version(id) ON DELETE CASCADE,
    local_uri       TEXT,
    provider        TEXT,
    external_id     TEXT,
    cache           TEXT CHECK (cache IN ('not_cached', 'partial', 'cached')),
    format          TEXT NOT NULL,
    bitrate_kbps    INTEGER,
    sample_rate_hz  INTEGER,
    bit_depth       INTEGER,
    availability    TEXT NOT NULL CHECK (availability IN ('available', 'degraded', 'unavailable')),
    last_checked_at INTEGER,
    CHECK (
        (local_uri IS NOT NULL AND provider IS NULL AND external_id IS NULL AND cache IS NULL)
        OR (local_uri IS NULL AND provider IS NOT NULL AND external_id IS NOT NULL AND cache IS NOT NULL)
    )
) STRICT;

-- Проекция журнала ------------------------------------------------------

CREATE TABLE track_user (
    track          BLOB PRIMARY KEY,
    liked          INTEGER NOT NULL CHECK (liked IN (0, 1)),
    rating         INTEGER CHECK (rating BETWEEN 1 AND 5),
    play_count     INTEGER NOT NULL CHECK (play_count >= 0),
    last_played_at INTEGER
) STRICT;

CREATE TABLE playlist (
    id         BLOB PRIMARY KEY,
    name       TEXT NOT NULL,
    kind       TEXT NOT NULL CHECK (kind IN ('manual')),
    created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE playlist_entry (
    id       BLOB PRIMARY KEY,
    playlist BLOB NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
    track    BLOB NOT NULL,
    position TEXT NOT NULL,
    added_at INTEGER NOT NULL
) STRICT;

CREATE TABLE play_event (
    id                 BLOB PRIMARY KEY,
    track              BLOB NOT NULL,
    version            BLOB,
    source             BLOB,
    started_at         INTEGER NOT NULL,
    utc_offset_minutes INTEGER NOT NULL,
    listened_ms        INTEGER NOT NULL CHECK (listened_ms >= 0),
    track_length_ms    INTEGER,
    skipped_at_ms      INTEGER,
    output             TEXT NOT NULL,
    previous_track     BLOB
) STRICT;

CREATE TABLE merge_decision (
    id         BLOB PRIMARY KEY,
    track_low  BLOB NOT NULL,
    track_high BLOB NOT NULL,
    verdict    TEXT NOT NULL CHECK (verdict IN ('merge', 'split')),
    by_user    INTEGER NOT NULL CHECK (by_user IN (0, 1)),
    basis      TEXT CHECK (basis IN ('mbid', 'fingerprint', 'normalized')),
    confidence REAL,
    decided_at INTEGER NOT NULL,
    CHECK (track_low < track_high)
) STRICT;

CREATE TABLE subscription (
    artist BLOB PRIMARY KEY,
    since  INTEGER NOT NULL
) STRICT;

CREATE TABLE block_entry (
    kind   TEXT NOT NULL CHECK (kind IN ('track', 'artist')),
    target BLOB NOT NULL,
    since  INTEGER NOT NULL,
    PRIMARY KEY (kind, target)
) STRICT;

CREATE TABLE setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) STRICT;
";

/// Индексы (B2.1): списки экранов, поиск, будущая склейка по отпечатку.
const INDEXES: &str = "
CREATE INDEX artist_name ON artist(name_normalized);
CREATE INDEX album_title ON album(title_normalized);
CREATE INDEX album_artist_by_artist ON album_artist(artist);
CREATE INDEX track_title ON track(title_normalized);
CREATE INDEX track_artist_name ON track(artist_normalized);
CREATE INDEX track_added ON track(added_at);
CREATE INDEX track_artist_by_artist ON track_artist(artist);
CREATE INDEX version_track ON version(track);
CREATE INDEX version_album ON version(album);
CREATE INDEX version_fingerprint ON version(fingerprint) WHERE fingerprint IS NOT NULL;
CREATE INDEX source_version ON source(version);
CREATE UNIQUE INDEX source_local ON source(local_uri) WHERE local_uri IS NOT NULL;
CREATE UNIQUE INDEX source_remote ON source(provider, external_id) WHERE provider IS NOT NULL;
CREATE INDEX playlist_entry_order ON playlist_entry(playlist, position, id);
CREATE INDEX play_event_track ON play_event(track, started_at);
CREATE INDEX play_event_time ON play_event(started_at);
CREATE INDEX merge_decision_pair ON merge_decision(track_low, track_high);
";
