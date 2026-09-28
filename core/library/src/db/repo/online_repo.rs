//! Треки провайдеров в каталоге (E3, ответ автора E1: «каталог при
//! действии»). Результаты поиска живут в памяти; сыграли, лайкнули,
//! добавили в плейлист — трек заводится здесь и дальше живёт как локальный.
//! Вкладки библиотеки его не показывают (они — музыка на устройстве),
//! «Любимое», плейлисты и история — показывают.

use plinth_types::{AlbumId, ArtistId, Availability, CoreError, ProviderId, SourceId, Timestamp, TrackId, VersionId};
use rusqlite::OptionalExtension;

use crate::db::Database;
use crate::db::sql::{Storage, id};
use crate::model::{
    Album, AlbumPlacement, Artist, CacheState, Explicitness, OnlineSource, OnlineTrack, Source, SourceLocation, Track,
    Version, VersionKind,
};
use crate::text::normalize;

impl Database {
    /// Заводит трек провайдера или находит уже заведённый по любому из его
    /// источников; недостающие варианты дописывает к нему. Артист и альбом —
    /// как у скана: по нормализованному имени, иначе новые. Одна транзакция.
    /// Трек без источников — `Parse`: играть его нечем.
    pub fn add_online_track(&self, track: &OnlineTrack, now: Timestamp) -> Result<TrackId, CoreError> {
        if track.sources.is_empty() {
            return Err(CoreError::parse("online track: no sources"));
        }
        self.in_transaction(|db| {
            if let Some((track_id, version)) = db.known_online(&track.sources)? {
                db.save_online_sources(version, &track.sources)?;
                return Ok(track_id);
            }
            let track_id = TrackId::new();
            db.insert_online(track_id, track, now)?;
            Ok(track_id)
        })
    }

    /// Сетевой трек журнала после переустановки (C4 + E3): заводится по
    /// паспорту с ID из журнала — лайки и плейлисты журнала сразу на нём.
    /// Его источники уже в каталоге у другого трека — не заводится (`false`).
    pub fn restore_online_track(&self, id: TrackId, track: &OnlineTrack, now: Timestamp) -> Result<bool, CoreError> {
        if track.sources.is_empty() {
            return Ok(false);
        }
        let restore = |db: &Self| {
            if db.known_online(&track.sources)?.is_some() || db.track(id)?.is_some() {
                return Ok(false);
            }
            db.insert_online(id, track, now)?;
            Ok(true)
        };
        // Перепривязка зовёт это внутри своей транзакции; вложенная — ошибка.
        if self.conn().is_autocommit() { self.in_transaction(restore) } else { restore(self) }
    }

    fn insert_online(&self, track_id: TrackId, track: &OnlineTrack, now: Timestamp) -> Result<(), CoreError> {
        let artist = track.artist.as_deref().map(|name| self.artist_by_name(name)).transpose()?;
        let album = match track.album.as_deref() {
            Some(title) => {
                Some(self.album_by_title(title, track.artist.as_deref().unwrap_or(""), artist, track.year)?)
            }
            None => None,
        };
        self.save_track(&Track {
            id: track_id,
            title: track.title.clone(),
            artist_credit: track.artist.clone().unwrap_or_default(),
            sort_artist_credit: None,
            artists: artist.into_iter().collect(),
            mbid_work: None,
            added_at: now,
        })?;
        let version = VersionId::new();
        self.save_version(&Version {
            id: version,
            track: track_id,
            kind: VersionKind::Original,
            explicitness: Explicitness::Unknown,
            duration: track.duration,
            album: album.map(|album| AlbumPlacement { album, disc: None, number: track.number }),
            release_year: track.year,
            mbid_recording: track.mbid,
            fingerprint: None,
        })?;
        self.save_online_sources(version, &track.sources)
    }

    /// Источники провайдеров трека — из них берётся адрес потока.
    pub fn online_sources(&self, track: TrackId) -> Result<Vec<OnlineSource>, CoreError> {
        let mut sources = Vec::new();
        for version in self.versions_of(track)? {
            for source in self.sources_of(version.id)? {
                if let SourceLocation::Provider { provider, external_id, .. } = source.location {
                    sources.push(OnlineSource { provider, external_id, audio: source.audio });
                }
            }
        }
        Ok(sources)
    }

    /// Уже заведённый трек провайдера — по любому из его источников; каталог
    /// не меняется. Экрану альбома — лайк трека, который ещё не играл.
    pub fn find_online_track(&self, track: &OnlineTrack) -> Result<Option<TrackId>, CoreError> {
        Ok(self.known_online(&track.sources)?.map(|(track, _)| track))
    }

    /// Трек и версия, у которых уже есть один из `sources`.
    fn known_online(&self, sources: &[OnlineSource]) -> Result<Option<(TrackId, VersionId)>, CoreError> {
        for source in sources {
            let found = self
                .conn()
                .query_row(
                    "SELECT v.track, v.id FROM source s JOIN version v ON v.id = s.version
                     WHERE s.provider = ?1 AND s.external_id = ?2",
                    [source.provider.as_str(), source.external_id.as_str()],
                    |row| Ok((id(row, "track")?, id(row, "id")?)),
                )
                .optional()
                .storage()?;
            if found.is_some() {
                return Ok(found);
            }
        }
        Ok(None)
    }

    /// Варианты, которых у версии ещё нет.
    fn save_online_sources(&self, version: VersionId, sources: &[OnlineSource]) -> Result<(), CoreError> {
        let known = self.online_sources_of_version(version)?;
        for source in
            sources.iter().filter(|source| !known.contains(&(source.provider.clone(), source.external_id.clone())))
        {
            self.save_source(&Source {
                id: SourceId::new(),
                version,
                location: SourceLocation::Provider {
                    provider: source.provider.clone(),
                    external_id: source.external_id.clone(),
                    cache: CacheState::NotCached,
                },
                audio: source.audio,
                availability: Availability::Available,
                last_checked_at: None,
            })?;
        }
        Ok(())
    }

    fn online_sources_of_version(&self, version: VersionId) -> Result<Vec<(ProviderId, String)>, CoreError> {
        Ok(self
            .sources_of(version)?
            .into_iter()
            .filter_map(|source| match source.location {
                SourceLocation::Provider { provider, external_id, .. } => Some((provider, external_id)),
                SourceLocation::Local { .. } => None,
            })
            .collect())
    }

    fn artist_by_name(&self, name: &str) -> Result<ArtistId, CoreError> {
        if let Some(artist) = self.artists_named(name)?.into_iter().next() {
            return Ok(artist.id);
        }
        let artist = Artist {
            id: ArtistId::new(),
            name: name.to_owned(),
            sort_name: None,
            mbid: None,
            aliases: Vec::new(),
            bio: None,
        };
        self.save_artist(&artist)?;
        Ok(artist.id)
    }

    fn album_by_title(
        &self,
        title: &str,
        artist_credit: &str,
        artist: Option<ArtistId>,
        year: Option<u16>,
    ) -> Result<AlbumId, CoreError> {
        let same_artist = |album: &Album| normalize(&album.artist_credit) == normalize(artist_credit);
        if let Some(album) = self.albums_titled(title)?.into_iter().find(same_artist) {
            return Ok(album.id);
        }
        let album = Album {
            id: AlbumId::new(),
            title: title.to_owned(),
            artist_credit: artist_credit.to_owned(),
            sort_artist_credit: None,
            artists: artist.into_iter().collect(),
            year,
            label: None,
            country: None,
            disc_count: None,
            mbid_release: None,
        };
        self.save_album(&album)?;
        Ok(album.id)
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{Bitrate, CoreError, Format, ProviderId, Timestamp};

    use crate::db::repo::fixtures::db;
    use crate::model::{AudioSpec, OnlineSource, OnlineTrack};

    fn source(external: &str, format: Format, kbps: Option<u32>) -> OnlineSource {
        OnlineSource {
            provider: ProviderId::new("archive.org").unwrap(),
            external_id: external.to_owned(),
            audio: AudioSpec { format, bitrate: kbps.map(Bitrate::kbps), sample_rate_hz: None, bit_depth: None },
        }
    }

    fn oh_doctor() -> OnlineTrack {
        OnlineTrack {
            title: "\"OH DOCTOR\"".to_owned(),
            artist: Some("NAOMI BROWN And Her Piano, H. Pearl".to_owned()),
            album: Some("\"OH DOCTOR\"".to_owned()),
            number: Some(1),
            year: None,
            duration: Some(Duration::from_millis(127_450)),
            mbid: None,
            sources: vec![source("78_oh/a.flac", Format::Flac, None), source("78_oh/a.mp3", Format::Mp3, Some(233))],
        }
    }

    const NOW: Timestamp = Timestamp::from_millis(1_790_000_000_000);

    #[test]
    fn online_track_becomes_track_version_and_sources() {
        let db = db();

        let id = db.add_online_track(&oh_doctor(), NOW).unwrap();

        let track = db.track(id).unwrap().unwrap();
        assert_eq!(track.title, "\"OH DOCTOR\"");
        assert_eq!(track.artist_credit, "NAOMI BROWN And Her Piano, H. Pearl");
        assert_eq!(track.artists.len(), 1);
        let versions = db.versions_of(id).unwrap();
        assert_eq!(versions.len(), 1);
        assert_eq!(versions[0].duration, Some(Duration::from_millis(127_450)));
        assert_eq!(versions[0].album.map(|a| a.number), Some(Some(1)));
        let album = db.album(versions[0].album.unwrap().album).unwrap().unwrap();
        assert_eq!(album.title, "\"OH DOCTOR\"");
        assert_eq!(db.online_sources(id).unwrap(), oh_doctor().sources);
    }

    #[test]
    fn the_same_track_twice_is_one_track() {
        let db = db();
        let first = db.add_online_track(&oh_doctor(), NOW).unwrap();

        let again = db.add_online_track(&oh_doctor(), NOW).unwrap();
        let by_one_variant = db
            .add_online_track(
                &OnlineTrack { sources: vec![source("78_oh/a.mp3", Format::Mp3, Some(233))], ..oh_doctor() },
                NOW,
            )
            .unwrap();

        assert_eq!((again, by_one_variant), (first, first));
        assert_eq!(db.online_sources(first).unwrap().len(), 2);
    }

    #[test]
    fn a_new_variant_joins_the_known_track() {
        let db = db();
        let first = db.add_online_track(&oh_doctor(), NOW).unwrap();
        let mut more = oh_doctor();
        more.sources.push(source("78_oh/a.ogg", Format::Vorbis, None));

        assert_eq!(db.add_online_track(&more, NOW).unwrap(), first);
        assert_eq!(db.online_sources(first).unwrap().len(), 3);
    }

    #[test]
    fn a_known_track_is_found_without_adding() {
        let db = db();
        assert_eq!(db.find_online_track(&oh_doctor()).unwrap(), None);

        let id = db.add_online_track(&oh_doctor(), NOW).unwrap();
        let by_one_variant =
            OnlineTrack { sources: vec![source("78_oh/a.mp3", Format::Mp3, Some(233))], ..oh_doctor() };
        let stranger = OnlineTrack { sources: vec![source("78_oh/b.mp3", Format::Mp3, None)], ..oh_doctor() };

        assert_eq!(db.find_online_track(&oh_doctor()).unwrap(), Some(id));
        assert_eq!(db.find_online_track(&by_one_variant).unwrap(), Some(id));
        assert_eq!(db.find_online_track(&stranger).unwrap(), None, "the same title is not the same track");
        assert_eq!(db.find_online_track(&OnlineTrack { sources: Vec::new(), ..oh_doctor() }).unwrap(), None);
    }

    #[test]
    fn artist_and_album_are_shared_like_in_a_scan() {
        let db = db();
        let one = db.add_online_track(&oh_doctor(), NOW).unwrap();
        let mut two = oh_doctor();
        two.title = "B side".to_owned();
        two.sources = vec![source("78_oh/b.flac", Format::Flac, None)];

        let two = db.add_online_track(&two, NOW).unwrap();

        let (one, two) = (db.track(one).unwrap().unwrap(), db.track(two).unwrap().unwrap());
        assert_eq!(one.artists, two.artists);
        let album_of = |track| db.versions_of(track).unwrap()[0].album.unwrap().album;
        assert_eq!(album_of(one.id), album_of(two.id));
    }

    #[test]
    fn without_artist_and_album_it_still_plays() {
        let db = db();
        let bare = OnlineTrack { artist: None, album: None, ..oh_doctor() };

        let id = db.add_online_track(&bare, NOW).unwrap();

        let track = db.track(id).unwrap().unwrap();
        assert_eq!(track.artist_credit, "");
        assert!(track.artists.is_empty());
        assert_eq!(db.versions_of(id).unwrap()[0].album, None);
    }

    #[test]
    fn a_track_without_sources_is_refused() {
        let db = db();

        let refused = db.add_online_track(&OnlineTrack { sources: Vec::new(), ..oh_doctor() }, NOW);

        assert!(matches!(refused, Err(CoreError::Parse { .. })));
    }

    #[test]
    fn a_journal_track_comes_back_with_its_own_id() {
        let db = db();
        let id = plinth_types::TrackId::new();

        assert!(db.restore_online_track(id, &oh_doctor(), NOW).unwrap());

        assert_eq!(db.track(id).unwrap().map(|t| t.title), Some("\"OH DOCTOR\"".to_owned()));
        assert_eq!(db.online_sources(id).unwrap(), oh_doctor().sources);
        assert!(!db.restore_online_track(id, &oh_doctor(), NOW).unwrap(), "already there");
    }

    #[test]
    fn sources_owned_by_another_track_are_not_taken() {
        let db = db();
        let owner = db.add_online_track(&oh_doctor(), NOW).unwrap();
        let journal = plinth_types::TrackId::new();

        assert!(!db.restore_online_track(journal, &oh_doctor(), NOW).unwrap());
        assert!(db.track(journal).unwrap().is_none());
        assert!(db.track(owner).unwrap().is_some());
        assert!(!db.restore_online_track(journal, &OnlineTrack { sources: Vec::new(), ..oh_doctor() }, NOW).unwrap());
    }
}
