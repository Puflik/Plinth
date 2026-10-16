//! Р9в, второй этап (`docs/work/r9c.md`): том пропавшего должен быть обойдён. Корень,
//! который есть среди готовых томов скана, но обход его не нашёл (карту вынули между
//! «система отдала» и «обход начался»), для скана — вынутая карта: его файлы пропали,
//! но неотличимо от «перенесли», и данные карты не уходят двойнику на другом томе.
//! Без корня в списке томов это проверяет `r9c_across_scans.rs`.

mod r9c_support;

use std::fs;
use std::path::PathBuf;

use plinth_library::db::Database;
use r9c_support::{FIRST_SCAN, Volume, like, liked, run, song, tagged, track_at, visible};

/// Страж: трек карты с лайком, копия на телефоне без данных. Карта в списке готовых
/// томов, но каталога нет (его убрали на время скана): трек карты остаётся скрытым на
/// своём пути, копия — своим треком; карта вернулась — трек оживает вместе с лайком.
#[test]
fn a_ready_volume_the_walk_did_not_find_does_not_give_its_data_to_a_copy_elsewhere() {
    let (card, phone) = (Volume::new(), Volume::new());
    card.put("Music/Song.mp3");
    phone.put("Music/Song.mp3");
    let db = Database::open_in_memory().unwrap();
    let reader = tagged(vec![("Song.mp3", song("Song", "Band", "Album", 180))]);
    run(&db, &[&card, &phone], &reader, FIRST_SCAN);
    let on_card = track_at(&db, &card, "Music/Song.mp3").unwrap();
    let on_phone = track_at(&db, &phone, "Music/Song.mp3").unwrap();
    assert_ne!(on_card, on_phone);
    like(&db, on_card);

    // Каталог карты уходит в сторону и возвращается: время изменения файлов то же.
    let away = PathBuf::from(format!("{}-away", card.0.display()));
    fs::rename(&card.0, &away).unwrap();
    let pulled = run(&db, &[&card, &phone], &reader, FIRST_SCAN + 1);
    fs::rename(&away, &card.0).unwrap();

    assert_eq!((pulled.missing_volumes, pulled.missing, pulled.moved), (1, 1, 0), "{pulled:?}");
    assert_eq!(track_at(&db, &phone, "Music/Song.mp3"), Some(on_phone));
    assert_eq!(track_at(&db, &card, "Music/Song.mp3"), Some(on_card), "трек карты — на пути карты");
    assert_eq!(visible(&db), [(on_phone, Some(phone.uri("Music/Song.mp3")))]);

    let back = run(&db, &[&card, &phone], &reader, FIRST_SCAN + 2);

    assert_eq!((back.returned, back.moved), (1, 0), "{back:?}");
    assert_eq!(visible(&db).len(), 2);
    assert!(liked(&db, on_card));
}
