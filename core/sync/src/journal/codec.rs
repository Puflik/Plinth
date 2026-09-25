//! Двоичный формат записи журнала (C2.2). Запись — значение в документе
//! `yrs`: заголовок (версия схемы, устройство, время) и поля сущности.
//! Идентификаторы — 16 байт, числа — varint: прослушивания — 85 % журнала, а
//! журнал обязан влезать в Auto Backup (ADR 0006).
//!
//! Совместимость — только дописыванием. Новая версия схемы добавляет поля в
//! конец записи; старый код читает известное начало и не смотрит на хвост.
//! Незнакомое значение перечисления делает запись нечитаемой — проекция её
//! пропустит, в документе она останется. Исключение — устройство вывода:
//! незнакомое читается как `Unknown`, прослушивание не теряется.
//!
//! Коды перечислений здесь — формат журнала, они не связаны с кодами базы
//! (`db/codes.rs` в `plinth-library`) и не меняются никогда.

use std::time::Duration;

use plinth_library::model::{
    DecidedBy, IdentityBasis, MergeDecision, OutputDevice, PlayEvent, Playlist, PlaylistEntry, PlaylistKind, Rating,
    Setting, TrackPair, Verdict, VersionPreference,
};
use plinth_types::{CoreError, DeviceId, EntityId, PlaylistEntryId, PlaylistId, Position, Timestamp};

use super::op_meta::OpMeta;

/// Запись с заголовком `meta` и полями, которые пишет `payload`.
pub(crate) fn encode(meta: &OpMeta, payload: impl FnOnce(&mut Writer)) -> Vec<u8> {
    let mut writer = Writer(Vec::with_capacity(64));
    writer.u8(meta.schema);
    writer.id(&meta.device);
    writer.timestamp(meta.at);
    payload(&mut writer);
    writer.0
}

/// Заголовок и поля записи. Хвост, дописанный более новой схемой, пропускается.
pub(crate) fn decode<T>(
    bytes: &[u8],
    payload: impl FnOnce(&mut Reader<'_>) -> Result<T, CoreError>,
) -> Result<(OpMeta, T), CoreError> {
    let mut reader = Reader { bytes };
    let schema = reader.u8()?;
    if schema == 0 {
        return Err(broken("schema 0"));
    }
    let meta = OpMeta { device: reader.id::<DeviceId>()?, at: reader.timestamp()?, schema };
    Ok((meta, payload(&mut reader)?))
}

fn broken(what: &str) -> CoreError {
    CoreError::parse(format!("journal record: {what}"))
}

pub(crate) struct Writer(Vec<u8>);

impl Writer {
    pub(crate) fn u8(&mut self, value: u8) {
        self.0.push(value);
    }

    /// LEB128: семь бит на байт, старший — «дальше ещё».
    fn uvar(&mut self, mut value: u64) {
        while value >= 0x80 {
            self.0.push((value as u8) | 0x80);
            value >>= 7;
        }
        self.0.push(value as u8);
    }

    /// Zigzag: малые по модулю отрицательные — тоже в один-два байта.
    fn ivar(&mut self, value: i64) {
        self.uvar(((value << 1) ^ (value >> 63)) as u64);
    }

    fn id<T: EntityId>(&mut self, id: &T) {
        self.0.extend_from_slice(id.as_bytes());
    }

    fn timestamp(&mut self, at: Timestamp) {
        self.ivar(at.as_millis());
    }

    fn duration(&mut self, duration: Duration) {
        self.uvar(u64::try_from(duration.as_millis()).unwrap_or(u64::MAX));
    }

    fn text(&mut self, text: &str) {
        self.uvar(text.len() as u64);
        self.0.extend_from_slice(text.as_bytes());
    }

    pub(crate) fn rating(&mut self, rating: Rating) {
        self.u8(rating.stars());
    }

    /// Плейлист без идентификатора: он — ключ записи.
    pub(crate) fn playlist(&mut self, playlist: &Playlist) {
        self.text(&playlist.name);
        self.u8(match playlist.kind {
            PlaylistKind::Manual => 0,
        });
        self.timestamp(playlist.created_at);
    }

    /// Запись плейлиста без идентификатора: он — ключ записи.
    pub(crate) fn entry(&mut self, entry: &PlaylistEntry) {
        self.id(&entry.playlist);
        self.id(&entry.track);
        self.text(&entry.position.to_string());
        self.timestamp(entry.added_at);
    }

    pub(crate) fn play(&mut self, play: &PlayEvent) {
        self.id(&play.id);
        self.id(&play.track);
        let flags = u8::from(play.version.is_some())
            | u8::from(play.source.is_some()) << 1
            | u8::from(play.track_length.is_some()) << 2
            | u8::from(play.skipped_at.is_some()) << 3
            | u8::from(play.previous_track.is_some()) << 4;
        self.u8(flags);
        if let Some(version) = &play.version {
            self.id(version);
        }
        if let Some(source) = &play.source {
            self.id(source);
        }
        self.timestamp(play.started_at);
        self.ivar(i64::from(play.utc_offset_minutes));
        self.duration(play.listened);
        if let Some(length) = play.track_length {
            self.duration(length);
        }
        if let Some(skipped) = play.skipped_at {
            self.duration(skipped);
        }
        self.u8(match play.output {
            OutputDevice::Speaker => 0,
            OutputDevice::Headphones => 1,
            OutputDevice::Bluetooth => 2,
            OutputDevice::Car => 3,
            OutputDevice::Cast => 4,
            OutputDevice::Unknown => 5,
        });
        if let Some(previous) = &play.previous_track {
            self.id(previous);
        }
    }

    pub(crate) fn decision(&mut self, decision: &MergeDecision) {
        self.id(&decision.id);
        let (low, high) = decision.pair.tracks();
        self.id(&low);
        self.id(&high);
        self.u8(match decision.verdict {
            Verdict::Merge => 0,
            Verdict::Split => 1,
        });
        match decision.by {
            DecidedBy::User => self.u8(0),
            DecidedBy::Auto { basis, confidence } => {
                self.u8(1);
                self.u8(match basis {
                    IdentityBasis::Mbid => 0,
                    IdentityBasis::Fingerprint => 1,
                    IdentityBasis::Normalized => 2,
                });
                self.0.extend_from_slice(&confidence.to_le_bytes());
            }
        }
        self.timestamp(decision.decided_at);
    }

    pub(crate) fn setting(&mut self, setting: Setting) {
        match setting {
            Setting::VersionPreference(preference) => {
                self.u8(0);
                self.u8(match preference {
                    VersionPreference::Original => 0,
                    VersionPreference::Clean => 1,
                    VersionPreference::Any => 2,
                });
            }
        }
    }

    /// Дата подписки или блокировки.
    pub(crate) fn since(&mut self, since: Timestamp) {
        self.timestamp(since);
    }
}

pub(crate) struct Reader<'a> {
    bytes: &'a [u8],
}

impl Reader<'_> {
    fn take(&mut self, count: usize) -> Result<&[u8], CoreError> {
        if self.bytes.len() < count {
            return Err(broken("cut short"));
        }
        let (head, rest) = self.bytes.split_at(count);
        self.bytes = rest;
        Ok(head)
    }

    fn u8(&mut self) -> Result<u8, CoreError> {
        Ok(self.take(1)?[0])
    }

    fn uvar(&mut self) -> Result<u64, CoreError> {
        let mut value = 0_u64;
        for shift in (0..64).step_by(7) {
            let byte = self.u8()?;
            value |= u64::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                return Ok(value);
            }
        }
        Err(broken("varint too long"))
    }

    fn ivar(&mut self) -> Result<i64, CoreError> {
        let raw = self.uvar()?;
        Ok((raw >> 1) as i64 ^ -((raw & 1) as i64))
    }

    fn id<T: EntityId>(&mut self) -> Result<T, CoreError> {
        let mut bytes = [0_u8; 16];
        bytes.copy_from_slice(self.take(16)?);
        Ok(T::from_bytes(bytes))
    }

    fn timestamp(&mut self) -> Result<Timestamp, CoreError> {
        self.ivar().map(Timestamp::from_millis)
    }

    fn duration(&mut self) -> Result<Duration, CoreError> {
        self.uvar().map(Duration::from_millis)
    }

    fn text(&mut self) -> Result<String, CoreError> {
        let length = usize::try_from(self.uvar()?).map_err(|_| broken("text too long"))?;
        String::from_utf8(self.take(length)?.to_vec()).map_err(|_| broken("text is not UTF-8"))
    }

    pub(crate) fn rating(&mut self) -> Result<Rating, CoreError> {
        Rating::new(self.u8()?)
    }

    pub(crate) fn playlist(&mut self, id: PlaylistId) -> Result<Playlist, CoreError> {
        let name = self.text()?;
        let kind = match self.u8()? {
            0 => PlaylistKind::Manual,
            other => return Err(broken(&format!("playlist kind {other}"))),
        };
        Ok(Playlist { id, name, kind, created_at: self.timestamp()? })
    }

    pub(crate) fn entry(&mut self, id: PlaylistEntryId) -> Result<PlaylistEntry, CoreError> {
        Ok(PlaylistEntry {
            id,
            playlist: self.id()?,
            track: self.id()?,
            position: self.text()?.parse::<Position>()?,
            added_at: self.timestamp()?,
        })
    }

    pub(crate) fn play(&mut self) -> Result<PlayEvent, CoreError> {
        let id = self.id()?;
        let track = self.id()?;
        let flags = self.u8()?;
        let has = |bit: u8| flags & (1 << bit) != 0;
        let version = if has(0) { Some(self.id()?) } else { None };
        let source = if has(1) { Some(self.id()?) } else { None };
        let started_at = self.timestamp()?;
        let utc_offset_minutes = i16::try_from(self.ivar()?).map_err(|_| broken("utc offset"))?;
        let listened = self.duration()?;
        let track_length = if has(2) { Some(self.duration()?) } else { None };
        let skipped_at = if has(3) { Some(self.duration()?) } else { None };
        let output = match self.u8()? {
            0 => OutputDevice::Speaker,
            1 => OutputDevice::Headphones,
            2 => OutputDevice::Bluetooth,
            3 => OutputDevice::Car,
            4 => OutputDevice::Cast,
            _ => OutputDevice::Unknown,
        };
        let previous_track = if has(4) { Some(self.id()?) } else { None };
        Ok(PlayEvent {
            id,
            track,
            version,
            source,
            started_at,
            utc_offset_minutes,
            listened,
            track_length,
            skipped_at,
            output,
            previous_track,
        })
    }

    pub(crate) fn decision(&mut self) -> Result<MergeDecision, CoreError> {
        let id = self.id()?;
        let pair = TrackPair::new(self.id()?, self.id()?)?;
        let verdict = match self.u8()? {
            0 => Verdict::Merge,
            1 => Verdict::Split,
            other => return Err(broken(&format!("verdict {other}"))),
        };
        let by = match self.u8()? {
            0 => DecidedBy::User,
            1 => {
                let basis = match self.u8()? {
                    0 => IdentityBasis::Mbid,
                    1 => IdentityBasis::Fingerprint,
                    2 => IdentityBasis::Normalized,
                    other => return Err(broken(&format!("identity basis {other}"))),
                };
                let mut bytes = [0_u8; 4];
                bytes.copy_from_slice(self.take(4)?);
                DecidedBy::Auto { basis, confidence: f32::from_le_bytes(bytes) }
            }
            other => return Err(broken(&format!("decided by {other}"))),
        };
        Ok(MergeDecision { id, pair, verdict, by, decided_at: self.timestamp()? })
    }

    pub(crate) fn setting(&mut self) -> Result<Setting, CoreError> {
        match self.u8()? {
            0 => {
                let preference = match self.u8()? {
                    0 => VersionPreference::Original,
                    1 => VersionPreference::Clean,
                    2 => VersionPreference::Any,
                    other => return Err(broken(&format!("version preference {other}"))),
                };
                Ok(Setting::VersionPreference(preference))
            }
            other => Err(broken(&format!("setting {other}"))),
        }
    }

    pub(crate) fn since(&mut self) -> Result<Timestamp, CoreError> {
        self.timestamp()
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_library::model::{
        DecidedBy, IdentityBasis, MergeDecision, OutputDevice, PlayEvent, Playlist, PlaylistEntry, PlaylistKind,
        Setting, TrackPair, Verdict, VersionPreference,
    };
    use plinth_types::{
        DeviceId, MergeDecisionId, PlayEventId, PlaylistEntryId, PlaylistId, Position, SourceId, Timestamp, TrackId,
        VersionId,
    };

    use super::{Reader, Writer, decode, encode};
    use crate::journal::op_meta::{OP_SCHEMA, OpMeta};

    fn meta() -> OpMeta {
        OpMeta { at: Timestamp::from_millis(1_790_307_000_000), device: DeviceId::new(), schema: OP_SCHEMA }
    }

    fn full_play() -> PlayEvent {
        PlayEvent {
            id: PlayEventId::new(),
            track: TrackId::new(),
            version: Some(VersionId::new()),
            source: Some(SourceId::new()),
            started_at: Timestamp::from_millis(1_790_307_000_000),
            utc_offset_minutes: -300,
            listened: Duration::from_millis(201_500),
            track_length: Some(Duration::from_secs(238)),
            skipped_at: Some(Duration::from_secs(201)),
            output: OutputDevice::Bluetooth,
            previous_track: Some(TrackId::new()),
        }
    }

    fn round_trip<T: PartialEq + std::fmt::Debug>(
        value: &T,
        write: impl FnOnce(&mut Writer, &T),
        read: impl FnOnce(&mut Reader<'_>) -> Result<T, plinth_types::CoreError>,
    ) -> usize {
        let meta = meta();
        let bytes = encode(&meta, |w| write(w, value));
        let (read_meta, read_value) = decode(&bytes, read).unwrap();
        assert_eq!(read_meta, meta);
        assert_eq!(&read_value, value);
        bytes.len()
    }

    #[test]
    fn plays_round_trip_with_and_without_optional_facts() {
        let full = full_play();
        let bare = PlayEvent {
            version: None,
            source: None,
            track_length: None,
            skipped_at: None,
            previous_track: None,
            output: OutputDevice::Unknown,
            ..full_play()
        };

        let full_size = round_trip(&full, |w, p| w.play(p), |r| r.play());
        let bare_size = round_trip(&bare, |w, p| w.play(p), |r| r.play());

        // Прослушивания — 85 % журнала (C1): запись должна оставаться компактной.
        assert!(full_size <= 130, "{full_size}");
        assert!(bare_size <= 70, "{bare_size}");
    }

    #[test]
    fn playlists_and_entries_round_trip() {
        let playlist = Playlist {
            id: PlaylistId::new(),
            name: "Дорога — ночь".to_owned(),
            kind: PlaylistKind::Manual,
            created_at: Timestamp::from_millis(5),
        };
        let entry = PlaylistEntry {
            id: PlaylistEntryId::new(),
            playlist: playlist.id,
            track: TrackId::new(),
            position: Position::after(&Position::first()),
            added_at: Timestamp::from_millis(6),
        };

        round_trip(&playlist, |w, p| w.playlist(p), |r| r.playlist(playlist.id));
        round_trip(&entry, |w, e| w.entry(e), |r| r.entry(entry.id));
    }

    #[test]
    fn decisions_round_trip() {
        let pair = TrackPair::new(TrackId::new(), TrackId::new()).unwrap();
        for by in [DecidedBy::User, DecidedBy::Auto { basis: IdentityBasis::Normalized, confidence: 0.625 }] {
            let decision = MergeDecision {
                id: MergeDecisionId::new(),
                pair,
                verdict: Verdict::Merge,
                by,
                decided_at: Timestamp::from_millis(9),
            };
            round_trip(&decision, |w, d| w.decision(d), |r| r.decision());
        }
    }

    #[test]
    fn settings_round_trip() {
        for preference in [VersionPreference::Original, VersionPreference::Clean, VersionPreference::Any] {
            let setting = Setting::VersionPreference(preference);
            round_trip(&setting, |w, s| w.setting(*s), |r| r.setting());
        }
    }

    #[test]
    fn a_cut_record_is_an_error() {
        let bytes = encode(&meta(), |w| w.play(&full_play()));

        for cut in [0, 1, 10, bytes.len() - 1] {
            assert!(decode(&bytes[..cut], |r| r.play()).is_err(), "{cut}");
        }
    }

    /// Схема растёт дописыванием в конец: старый код читает известное начало.
    #[test]
    fn a_newer_record_is_read_by_its_known_prefix() {
        let play = full_play();
        let mut bytes = encode(&meta(), |w| w.play(&play));
        bytes[0] = OP_SCHEMA + 1;
        bytes.extend_from_slice(b"field from the future");

        let (meta, read) = decode(&bytes, |r| r.play()).unwrap();

        assert_eq!(meta.schema, OP_SCHEMA + 1);
        assert_eq!(read, play);
    }

    #[test]
    fn schema_zero_is_not_a_record() {
        let mut bytes = encode(&meta(), |w| w.play(&full_play()));
        bytes[0] = 0;

        assert!(decode(&bytes, |r| r.play()).is_err());
    }

    /// Устройство вывода из будущей версии — «неизвестно», а не потеря прослушивания.
    #[test]
    fn unknown_output_device_reads_as_unknown() {
        let play = full_play();
        let bytes = encode(&meta(), |w| w.play(&play));
        let output = bytes.len() - 17;
        let mut patched = bytes.clone();
        patched[output] = 200;

        let (_, read) = decode(&patched, |r| r.play()).unwrap();

        assert_eq!(read, PlayEvent { output: OutputDevice::Unknown, ..play });
    }
}
