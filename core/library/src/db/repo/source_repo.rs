use plinth_types::{Availability, Bitrate, CoreError, EntityId, SourceId, Timestamp, VersionId};
use rusqlite::{Row, params};

use crate::db::Database;
use crate::db::codes::{Code, column, opt_column};
use crate::db::sql::{Storage, id, invalid, opt_timestamp};
use crate::model::{AudioSpec, CacheState, ProviderId, Source, SourceLocation};

impl Database {
    pub fn save_source(&self, source: &Source) -> Result<(), CoreError> {
        let (local_uri, provider, external_id, cache): (
            Option<&str>,
            Option<String>,
            Option<&str>,
            Option<CacheState>,
        ) = match &source.location {
            SourceLocation::Local { uri } => (Some(uri), None, None, None),
            SourceLocation::Provider { provider, external_id, cache } => {
                (None, Some(provider.to_string()), Some(external_id), Some(*cache))
            }
        };
        let audio = &source.audio;
        self.conn()
            .execute(
                // UPSERT по id: второй источник с тем же файлом или треком
                // провайдера — ошибка, а не молчаливая подмена первого.
                "INSERT INTO source(id, version, local_uri, provider, external_id, cache, format,
                                    bitrate_kbps, sample_rate_hz, bit_depth, availability, last_checked_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
                 ON CONFLICT(id) DO UPDATE SET
                     version = excluded.version, local_uri = excluded.local_uri, provider = excluded.provider,
                     external_id = excluded.external_id, cache = excluded.cache, format = excluded.format,
                     bitrate_kbps = excluded.bitrate_kbps, sample_rate_hz = excluded.sample_rate_hz,
                     bit_depth = excluded.bit_depth, availability = excluded.availability,
                     last_checked_at = excluded.last_checked_at",
                params![
                    source.id.as_bytes(),
                    source.version.as_bytes(),
                    local_uri,
                    provider,
                    external_id,
                    cache.map(Code::code),
                    audio.format.code(),
                    audio.bitrate.map(Bitrate::as_kbps),
                    audio.sample_rate_hz,
                    audio.bit_depth,
                    source.availability.code(),
                    source.last_checked_at.map(Timestamp::as_millis)
                ],
            )
            .storage()
            .map(drop)
    }

    pub fn sources_of(&self, version: VersionId) -> Result<Vec<Source>, CoreError> {
        let mut statement = self.conn().prepare("SELECT * FROM source WHERE version = ?1 ORDER BY id").storage()?;
        statement.query_map([version.as_bytes()], read_source).storage()?.collect::<Result<_, _>>().storage()
    }

    /// Итог проверки доступности (скан, ответ провайдера).
    pub fn set_availability(
        &self,
        source: SourceId,
        availability: Availability,
        checked_at: Timestamp,
    ) -> Result<(), CoreError> {
        let changed = self
            .conn()
            .execute(
                "UPDATE source SET availability = ?2, last_checked_at = ?3 WHERE id = ?1",
                params![source.as_bytes(), availability.code(), checked_at.as_millis()],
            )
            .storage()?;
        if changed == 0 { Err(CoreError::unavailable(format!("source {source} not found"))) } else { Ok(()) }
    }
}

fn read_source(row: &Row<'_>) -> rusqlite::Result<Source> {
    let local_uri: Option<String> = row.get("local_uri")?;
    let location = match local_uri {
        Some(uri) => SourceLocation::Local { uri },
        None => {
            let provider: String = row.get("provider")?;
            SourceLocation::Provider {
                provider: ProviderId::new(&provider).map_err(|_| invalid("provider", &provider))?,
                external_id: row.get("external_id")?,
                cache: opt_column(row, "cache")?.unwrap_or(CacheState::NotCached),
            }
        }
    };
    Ok(Source {
        id: id(row, "id")?,
        version: id(row, "version")?,
        location,
        audio: AudioSpec {
            format: column(row, "format")?,
            bitrate: row.get::<_, Option<u32>>("bitrate_kbps")?.map(Bitrate::kbps),
            sample_rate_hz: row.get("sample_rate_hz")?,
            bit_depth: row.get("bit_depth")?,
        },
        availability: column(row, "availability")?,
        last_checked_at: opt_timestamp(row, "last_checked_at")?,
    })
}

#[cfg(test)]
mod tests {
    use plinth_types::{Availability, CoreError, Format, SourceId, Timestamp};

    use crate::db::repo::fixtures::{creep, db, local_source};
    use crate::model::{AudioSpec, CacheState, ProviderId, SourceLocation};

    #[test]
    fn local_and_provider_sources_round_trip() {
        let db = db();
        let (_, _, _, version, local) = creep(&db);
        let mut stream = local_source(&version, "unused");
        stream.location = SourceLocation::Provider {
            provider: ProviderId::new("archive.org").unwrap(),
            external_id: "radiohead1993-05-12".to_owned(),
            cache: CacheState::Partial,
        };
        stream.audio = AudioSpec { format: Format::Opus, bitrate: None, sample_rate_hz: None, bit_depth: None };
        db.save_source(&stream).unwrap();

        let mut expected = vec![local, stream];
        expected.sort_by_key(|s| s.id);
        assert_eq!(db.sources_of(version.id).unwrap(), expected);
    }

    /// Один файл — один источник: второй источник с тем же путём база не примет.
    #[test]
    fn same_local_file_twice_is_refused() {
        let db = db();
        let (_, _, _, version, local) = creep(&db);
        let SourceLocation::Local { uri } = &local.location else { unreachable!() };

        assert!(db.save_source(&local_source(&version, uri)).is_err());
    }

    #[test]
    fn availability_is_updated_with_the_check_time() {
        let db = db();
        let (_, _, _, version, local) = creep(&db);

        db.set_availability(local.id, Availability::Unavailable, Timestamp::from_millis(77)).unwrap();

        let stored = &db.sources_of(version.id).unwrap()[0];
        assert_eq!(
            (stored.availability, stored.last_checked_at),
            (Availability::Unavailable, Some(Timestamp::from_millis(77)))
        );
        assert!(matches!(
            db.set_availability(SourceId::new(), Availability::Available, Timestamp::from_millis(1)),
            Err(CoreError::Unavailable { .. })
        ));
    }
}
