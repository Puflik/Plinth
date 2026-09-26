//! Артисты и альбом файла в каталоге (D2.2): найти по имени или завести.
//!
//! ID не выводятся из тегов (B1): тот же артист — это строка с тем же именем
//! после нормализации, тот же альбом — то же название и тот же исполнитель
//! альбома.
//!
//! Имя для сортировки из тегов файла достаётся артисту и альбому. Нет его в
//! этом файле — остаётся прежнее: оно могло прийти из другого файла.

use plinth_types::{AlbumId, ArtistId, CoreError};

use super::Tags;
use crate::db::Database;
use crate::model::{Album, AlbumPlacement, Artist};
use crate::text::normalize;

/// Исполнитель сборника, у которого в тегах нет своего album artist.
const VARIOUS_ARTISTS: &str = "Various Artists";

/// ID артистов по именам, по порядку: известный по имени или новый. Имена для
/// сортировки — из тегов `tags`.
pub fn artist_ids(db: &Database, names: &[String], tags: &Tags) -> Result<Vec<ArtistId>, CoreError> {
    let mut ids = Vec::with_capacity(names.len());
    for name in names {
        let sort_name = tags.sort_name(name).map(str::to_owned);
        let id = match db.artists_named(name)?.into_iter().next() {
            Some(mut known) => {
                if sort_name.is_some() && known.sort_name != sort_name {
                    known.sort_name = sort_name;
                    db.save_artist(&known)?;
                }
                known.id
            }
            None => {
                let artist = Artist {
                    id: ArtistId::new(),
                    name: name.clone(),
                    sort_name,
                    mbid: None,
                    aliases: Vec::new(),
                    bio: None,
                };
                db.save_artist(&artist)?;
                artist.id
            }
        };
        if !ids.contains(&id) {
            ids.push(id);
        }
    }
    Ok(ids)
}

/// Место файла на альбоме; `None` — в тегах нет альбома.
///
/// Исполнитель альбома — album artist; у сборника без него — «Various
/// Artists»; иначе основной артист трека, чтобы гость из «A feat. B» не
/// уводил трек в отдельный альбом.
pub fn placement(db: &Database, tags: &Tags) -> Result<Option<AlbumPlacement>, CoreError> {
    let Some(title) = &tags.album else { return Ok(None) };
    let (credit, sort, names): (String, Option<String>, &[String]) = match &tags.album_artist {
        Some(credit) => (credit.clone(), tags.sort_album_artist.clone(), &tags.album_artists),
        None if tags.compilation => (VARIOUS_ARTISTS.to_owned(), None, &[]),
        None => match tags.artists.first() {
            Some(main) => (main.clone(), tags.sort_name(main).map(str::to_owned), std::slice::from_ref(main)),
            None => (String::new(), None, &[]),
        },
    };
    let key = normalize(&credit);
    let known = db.albums_titled(title)?.into_iter().find(|album| normalize(&album.artist_credit) == key);
    let album = match known {
        Some(mut album) => {
            // Имена для сортировки из этого файла — и артистам известного альбома.
            artist_ids(db, names, tags)?;
            let (year, discs) = (album.year.or(tags.year), album.disc_count.max(tags.disc_total));
            let sort = sort.or_else(|| album.sort_artist_credit.clone());
            if (year, discs, &sort) != (album.year, album.disc_count, &album.sort_artist_credit) {
                (album.year, album.disc_count, album.sort_artist_credit) = (year, discs, sort);
                db.save_album(&album)?;
            }
            album.id
        }
        None => {
            let album = Album {
                id: AlbumId::new(),
                title: title.clone(),
                artist_credit: credit,
                sort_artist_credit: sort,
                artists: artist_ids(db, names, tags)?,
                year: tags.year,
                label: None,
                country: None,
                disc_count: tags.disc_total,
                mbid_release: None,
            };
            db.save_album(&album)?;
            album.id
        }
    };
    Ok(Some(AlbumPlacement { album, disc: tags.disc, number: tags.track }))
}
