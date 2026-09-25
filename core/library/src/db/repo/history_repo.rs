use plinth_types::{CoreError, EntityId, Timestamp, TrackId};
use rusqlite::{OptionalExtension, Row, params};

use crate::db::Database;
use crate::db::codes::{Code, column};
use crate::db::sql::{Storage, id, millis, opt_duration, opt_id, opt_timestamp, timestamp};
use crate::model::{PlayEvent, Rating, TrackUserData};

impl Database {
    /// Прослушивание в историю; `false` — такое уже записано. Счётчики
    /// [`TrackUserData`] отсюда не пересчитываются: это дело проекции
    /// журнала (C3) по правилу [`PlayEvent::counts`].
    pub fn record_play(&self, event: &PlayEvent) -> Result<bool, CoreError> {
        self.conn()
            .prepare_cached(
                "INSERT INTO play_event(id, track, version, source, started_at, utc_offset_minutes, listened_ms,
                                        track_length_ms, skipped_at_ms, output, previous_track)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
                 ON CONFLICT(id) DO NOTHING",
            )
            .and_then(|mut statement| {
                statement.execute(params![
                    event.id.as_bytes(),
                    event.track.as_bytes(),
                    event.version.map(|v| *v.as_bytes()),
                    event.source.map(|s| *s.as_bytes()),
                    event.started_at.as_millis(),
                    event.utc_offset_minutes,
                    millis(event.listened),
                    event.track_length.map(millis),
                    event.skipped_at.map(millis),
                    event.output.code(),
                    event.previous_track.map(|t| *t.as_bytes())
                ])
            })
            .storage()
            .map(|inserted| inserted == 1)
    }

    /// Последние прослушивания всей библиотеки, новые первыми.
    pub fn recent_plays(&self, limit: u32) -> Result<Vec<PlayEvent>, CoreError> {
        let mut statement =
            self.conn().prepare("SELECT * FROM play_event ORDER BY started_at DESC, id DESC LIMIT ?1").storage()?;
        statement.query_map([limit], read_play).storage()?.collect::<Result<_, _>>().storage()
    }

    /// Последние прослушивания трека, новые первыми.
    pub fn plays_of(&self, track: TrackId, limit: u32) -> Result<Vec<PlayEvent>, CoreError> {
        let mut statement = self
            .conn()
            .prepare("SELECT * FROM play_event WHERE track = ?1 ORDER BY started_at DESC, id DESC LIMIT ?2")
            .storage()?;
        statement.query_map(params![track.as_bytes(), limit], read_play).storage()?.collect::<Result<_, _>>().storage()
    }

    /// Сохраняет пользовательское о треке. Пустое — без лайка, оценки и
    /// прослушиваний — строки не оставляет: так база после «лайк → снять»
    /// та же, что после пересборки из журнала (C3).
    pub fn save_user_data(&self, data: &TrackUserData) -> Result<(), CoreError> {
        if *data == TrackUserData::empty(data.track) {
            return self
                .conn()
                .execute("DELETE FROM track_user WHERE track = ?1", [data.track.as_bytes()])
                .storage()
                .map(drop);
        }
        self.conn()
            .prepare_cached(
                "INSERT OR REPLACE INTO track_user(track, liked, rating, play_count, last_played_at)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
            )
            .and_then(|mut statement| {
                statement.execute(params![
                    data.track.as_bytes(),
                    data.liked,
                    data.rating.map(Rating::stars),
                    data.play_count,
                    data.last_played_at.map(Timestamp::as_millis)
                ])
            })
            .storage()
            .map(drop)
    }

    /// Все непустые пользовательские данные, по треку.
    pub fn all_user_data(&self) -> Result<Vec<TrackUserData>, CoreError> {
        let mut statement = self.conn().prepare("SELECT * FROM track_user ORDER BY track").storage()?;
        statement.query_map([], read_user_data).storage()?.collect::<Result<_, _>>().storage()
    }

    /// Пользовательское о треке; не слушали и не оценивали — пустое.
    pub fn user_data(&self, track: TrackId) -> Result<TrackUserData, CoreError> {
        let found = self
            .conn()
            .query_row("SELECT * FROM track_user WHERE track = ?1", [track.as_bytes()], read_user_data)
            .optional()
            .storage()?;
        Ok(found.unwrap_or_else(|| TrackUserData::empty(track)))
    }
}

fn read_play(row: &Row<'_>) -> rusqlite::Result<PlayEvent> {
    Ok(PlayEvent {
        id: id(row, "id")?,
        track: id(row, "track")?,
        version: opt_id(row, "version")?,
        source: opt_id(row, "source")?,
        started_at: timestamp(row, "started_at")?,
        utc_offset_minutes: row.get("utc_offset_minutes")?,
        listened: opt_duration(row, "listened_ms")?.unwrap_or_default(),
        track_length: opt_duration(row, "track_length_ms")?,
        skipped_at: opt_duration(row, "skipped_at_ms")?,
        output: column(row, "output")?,
        previous_track: opt_id(row, "previous_track")?,
    })
}

fn read_user_data(row: &Row<'_>) -> rusqlite::Result<TrackUserData> {
    let rating = row
        .get::<_, Option<u8>>("rating")?
        .map(|stars| Rating::new(stars).map_err(|_| crate::db::sql::invalid("rating", &stars.to_string())))
        .transpose()?;
    Ok(TrackUserData {
        track: id(row, "track")?,
        liked: row.get("liked")?,
        rating,
        play_count: row.get("play_count")?,
        last_played_at: opt_timestamp(row, "last_played_at")?,
    })
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use plinth_types::{PlayEventId, Timestamp, TrackId};

    use crate::db::repo::fixtures::db;
    use crate::model::{OutputDevice, PlayEvent, Rating, TrackUserData};

    fn play(track: TrackId, at: i64) -> PlayEvent {
        PlayEvent {
            id: PlayEventId::new(),
            track,
            version: None,
            source: None,
            started_at: Timestamp::from_millis(at),
            utc_offset_minutes: 180,
            listened: Duration::from_secs(200),
            track_length: Some(Duration::from_secs(238)),
            skipped_at: Some(Duration::from_secs(200)),
            output: OutputDevice::Bluetooth,
            previous_track: Some(TrackId::new()),
        }
    }

    #[test]
    fn plays_come_back_newest_first_and_limited() {
        let db = db();
        let track = TrackId::new();
        let plays: Vec<PlayEvent> = [1_000, 3_000, 2_000].iter().map(|&at| play(track, at)).collect();
        for event in &plays {
            db.record_play(event).unwrap();
        }
        db.record_play(&play(TrackId::new(), 9_000)).unwrap();

        let recent = db.plays_of(track, 2).unwrap();

        assert_eq!(recent, vec![plays[1], plays[2]]);
    }

    #[test]
    fn user_data_round_trips_and_defaults_to_empty() {
        let db = db();
        let track = TrackId::new();
        assert_eq!(db.user_data(track).unwrap(), TrackUserData::empty(track));

        let data = TrackUserData {
            track,
            liked: true,
            rating: Some(Rating::new(4).unwrap()),
            play_count: 47,
            last_played_at: Some(Timestamp::from_millis(5)),
        };
        db.save_user_data(&data).unwrap();

        assert_eq!(db.user_data(track).unwrap(), data);
    }

    /// Пустые данные строки не оставляют: база после «лайк → снять» та же,
    /// что без лайка вовсе, — так проекция совпадает с пересборкой (C3).
    #[test]
    fn emptied_user_data_leaves_no_row() {
        let db = db();
        let track = TrackId::new();
        db.save_user_data(&TrackUserData { liked: true, ..TrackUserData::empty(track) }).unwrap();
        assert_eq!(db.all_user_data().unwrap().len(), 1);

        db.save_user_data(&TrackUserData::empty(track)).unwrap();

        assert!(db.all_user_data().unwrap().is_empty());
    }

    /// Повтор той же записи истории — не ошибка и не второе прослушивание.
    #[test]
    fn recording_the_same_play_twice_is_a_no_op() {
        let db = db();
        let event = play(TrackId::new(), 1_000);

        assert!(db.record_play(&event).unwrap());
        assert!(!db.record_play(&event).unwrap());

        assert_eq!(db.plays_of(event.track, 10).unwrap(), vec![event]);
    }

    #[test]
    fn recent_plays_span_all_tracks_newest_first() {
        let db = db();
        let (old, new) = (play(TrackId::new(), 1_000), play(TrackId::new(), 2_000));
        db.record_play(&old).unwrap();
        db.record_play(&new).unwrap();

        assert_eq!(db.recent_plays(10).unwrap(), vec![new, old]);
        assert_eq!(db.recent_plays(1).unwrap(), vec![new]);
    }

    /// Оценку вне 1–5 база не примет, даже если её записали в обход модели.
    #[test]
    fn schema_rejects_a_broken_rating() {
        let db = db();

        let result = db
            .conn()
            .execute("INSERT INTO track_user(track, liked, rating, play_count) VALUES (?1, 0, 9, 0)", [[7_u8; 16]]);

        assert!(result.is_err());
    }
}
