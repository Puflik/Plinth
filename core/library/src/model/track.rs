use plinth_types::{ArtistId, CoreError, Mbid, Timestamp, TrackId};

/// Песня — логическая, без привязки к записи и файлу (plan.md 2.1).
/// Каталог: пересобирается сканом и провайдерами.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Track {
    pub id: TrackId,
    /// Каноническое название: из MusicBrainz, если известно, иначе из тегов.
    pub title: String,
    /// Исполнитель строкой, как его показать: «Radiohead», «Daft Punk feat. Pharrell».
    pub artist_credit: String,
    /// Исполнитель для сортировки из тегов: «Bowie, David». Нет — сортируется
    /// по `artist_credit`.
    pub sort_artist_credit: Option<String>,
    /// Артисты по порядку указания, основной — первый.
    pub artists: Vec<ArtistId>,
    /// Произведение MusicBrainz — «золотой» уровень склейки (plan.md 4.2).
    pub mbid_work: Option<Mbid>,
    pub added_at: Timestamp,
}

/// Пользовательское о песне (plan.md 2.1): одно на все её версии. Идёт из
/// журнала (C2) — это проекция, не каталог.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrackUserData {
    pub track: TrackId,
    pub liked: bool,
    pub rating: Option<Rating>,
    pub play_count: u32,
    pub last_played_at: Option<Timestamp>,
}

impl TrackUserData {
    /// Песня, которую ещё не слушали и не оценивали.
    pub fn empty(track: TrackId) -> Self {
        Self { track, liked: false, rating: None, play_count: 0, last_played_at: None }
    }
}

/// Оценка звёздами, от одной до пяти (plan.md 12). Лайк — отдельно.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Rating(u8);

impl Rating {
    pub fn new(stars: u8) -> Result<Self, CoreError> {
        if (1..=5).contains(&stars) {
            Ok(Self(stars))
        } else {
            Err(CoreError::parse(format!("rating: {stars} stars, expected 1..=5")))
        }
    }

    pub fn stars(self) -> u8 {
        self.0
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::CoreError;

    use super::{Rating, TrackUserData};
    use plinth_types::TrackId;

    #[test]
    fn rating_is_one_to_five_stars() {
        for stars in 1..=5 {
            assert_eq!(Rating::new(stars).map(Rating::stars), Ok(stars));
        }
        for stars in [0, 6, 255] {
            assert!(matches!(Rating::new(stars), Err(CoreError::Parse { .. })), "{stars}");
        }
    }

    #[test]
    fn new_track_has_no_user_data_yet() {
        let track = TrackId::new();

        let data = TrackUserData::empty(track);

        assert_eq!(data.track, track);
        assert!(!data.liked);
        assert_eq!(data.rating, None);
        assert_eq!(data.play_count, 0);
        assert_eq!(data.last_played_at, None);
    }
}
