//! Р9д (`docs/work/r9e.md`, ревью R1, находки 5 и 20): журнал начат заново из базы
//! (`start_over`) — копия в папке, снятая до порчи, больше не воскрешает удалённое и
//! не предлагается ядром, а её паспорта по-прежнему возвращают данные треков, которых
//! нет в каталоге. Сценарии идут через `Core`, как их проходит приложение: порча
//! снимка, новое открытие, действия человека, «Восстановить» из папки.

use std::fs;

use plinth_library::model::Rating;
use plinth_types::{DeviceId, PlaylistId, TrackId};

use super::mirror_api::MirrorFile;
use super::test_api::TestFile;
use crate::session::{Core, DEVICE, JOURNAL};
use crate::testing::Scratch;

const KINO: &str = "/storage/emulated/0/Music/Кино/Кукушка.mp3";
const QUEEN: &str = "/storage/emulated/0/Music/Queen/Bohemian Rhapsody.mp3";

fn file(uri: &str, artist: &str, title: &str) -> TestFile {
    TestFile {
        uri: uri.to_owned(),
        folder: "Music/".to_owned(),
        title: Some(title.to_owned()),
        artist: Some(artist.to_owned()),
        album: None,
        album_artist: None,
        disc: None,
        number: None,
        duration_ms: Some(300_000),
    }
}

fn kino() -> TestFile {
    file(KINO, "Кино", "Кукушка")
}

fn queen() -> TestFile {
    file(QUEEN, "Queen", "Bohemian Rhapsody")
}

fn stars(count: u8) -> Rating {
    Rating::new(count).unwrap()
}

fn at(core: &Core, path: &str) -> TrackId {
    core.track_at(path.to_owned()).unwrap().unwrap()
}

/// Сворачивает журнал ядра в снимок: так выглядит журнал, проживший не один сеанс.
fn compact(dir: &Scratch) {
    let device: DeviceId = fs::read_to_string(dir.0.join(DEVICE)).unwrap().trim().parse().unwrap();
    plinth_sync::journal::Journal::open(&dir.0.join(JOURNAL), device).unwrap().compact().unwrap();
}

/// Последний байт снимка другой — сумма не сходится.
fn damage(dir: &Scratch) {
    let path = dir.0.join(JOURNAL).join("snapshot");
    let mut bytes = fs::read(&path).unwrap();
    let last = bytes.len() - 1;
    bytes[last] ^= 0xFF;
    fs::write(&path, &bytes).unwrap();
}

/// Ядро закрыто, снимок свёрнут и испорчен; следующее открытие начнёт журнал заново.
fn corrupt(dir: &Scratch) {
    compact(dir);
    damage(dir);
}

fn dup(file: &MirrorFile) -> MirrorFile {
    MirrorFile { name: file.name.clone(), content: file.content.clone() }
}

fn liked(core: &Core) -> Vec<String> {
    core.liked_tracks().unwrap().into_iter().map(|row| row.title).collect()
}

fn playlist_names(core: &Core) -> Vec<String> {
    core.playlists().unwrap().into_iter().map(|playlist| playlist.name).collect()
}

/// Старая установка: лайк `kino`, 5★ у `queen`, плейлист «Road» с `queen`. Возвращает
/// плейлист и копию, как её положило бы зеркало.
fn old_installation(dir: &Scratch, kino: TrackId, queen: TrackId) -> (PlaylistId, MirrorFile) {
    let core = Core::open(dir.path()).unwrap();
    core.like(kino).unwrap();
    core.rate(queen, Some(stars(5))).unwrap();
    let road = core.create_playlist("Road".to_owned()).unwrap();
    core.add_to_playlist(road.id, queen, None).unwrap();
    // Лайк, который остаётся: из базы есть что переносить, значит, журнал не пуст.
    core.like(TrackId::new()).unwrap();
    (road.id, core.mirror_copy().unwrap())
}

/// Находка 5: человек удалил плейлист, снял оценку и лайк уже после того, как журнал
/// начался заново, — копия, снятая до порчи, их не возвращает: ядро не предлагает её
/// («Восстановить» не светится) и не вливает, а если её всё же влили, журнал и база те же.
#[test]
fn a_copy_made_before_the_damage_does_not_bring_back_what_was_deleted_since() {
    let dir = Scratch::new();
    let (kino, queen) = (TrackId::new(), TrackId::new());
    let (road, stale) = old_installation(&dir, kino, queen);
    corrupt(&dir);
    let core = Core::open(dir.path()).unwrap();
    assert!(core.startup_report().unwrap().journal_started_over);
    core.delete_playlist(road).unwrap();
    core.rate(queen, None).unwrap();
    core.unlike(kino).unwrap();

    let found = core.inspect_mirror(vec![dup(&stale)]).unwrap();
    let restored = core.restore_mirror(vec![stale]).unwrap();

    assert!(!found.news, "the core offers a copy that has nothing the journal lacks: {found:?}");
    assert_eq!(playlist_names(&core), Vec::<String>::new(), "«Road» is back");
    assert_eq!(core.user_data(queen).unwrap().rating, None, "the rating is back");
    assert!(!core.user_data(kino).unwrap().liked, "the like is back");
    assert_eq!(restored.merged, 0);
}

/// Находка 5, «через месяц после переустановки»: в папке обе копии — старая и новая
/// (она помнит, что старая сменена), на новой установке «Восстановить» берёт их разом, в любом
/// порядке, — удалённое остаётся удалённым.
#[test]
fn a_reinstall_that_restores_both_copies_leaves_the_deletions_in_place() {
    let dir = Scratch::new();
    let (kino, queen) = (TrackId::new(), TrackId::new());
    let (road, stale) = old_installation(&dir, kino, queen);
    corrupt(&dir);
    let newer = {
        let core = Core::open(dir.path()).unwrap();
        core.delete_playlist(road).unwrap();
        core.rate(queen, None).unwrap();
        core.unlike(kino).unwrap();
        core.mirror_copy().unwrap()
    };
    assert_ne!(stale.name, newer.name);

    for forward in [true, false] {
        let fresh = Scratch::new();
        let core = Core::open(fresh.path()).unwrap();
        let files = if forward { vec![dup(&stale), dup(&newer)] } else { vec![dup(&newer), dup(&stale)] };

        let restored = core.restore_mirror(files).unwrap();

        assert_eq!(playlist_names(&core), Vec::<String>::new(), "forward={forward}: «Road» is back");
        assert_eq!(core.user_data(queen).unwrap().rating, None, "forward={forward}: the rating is back");
        assert!(!core.user_data(kino).unwrap().liked, "forward={forward}: the like is back");
        assert_eq!(restored.merged, 1, "forward={forward}: only the newer copy brings something");
    }
}

/// Две порчи подряд: вторая начинает журнал заново, и о первой сменённой установке знает
/// только копия, которую успела положить вторая. Обе прежние копии остаются в стороне.
#[test]
fn two_corruptions_in_a_row_keep_both_old_copies_aside() {
    let dir = Scratch::new();
    let (kino, queen) = (TrackId::new(), TrackId::new());
    let (road, first) = old_installation(&dir, kino, queen);
    corrupt(&dir);
    let second = {
        let core = Core::open(dir.path()).unwrap();
        core.delete_playlist(road).unwrap();
        core.mirror_copy().unwrap()
    };
    corrupt(&dir);
    let core = Core::open(dir.path()).unwrap();
    assert!(core.startup_report().unwrap().journal_started_over);

    let found = core.inspect_mirror(vec![dup(&first), dup(&second)]).unwrap();
    let restored = core.restore_mirror(vec![first, second]).unwrap();

    assert!(!found.news, "{found:?}");
    assert_eq!(playlist_names(&core), Vec::<String>::new(), "«Road» is back");
    assert_eq!(restored.merged, 0);
}

/// Находка 20: после переустановки трек с карты, которой ещё нет в каталоге, живёт в журнале
/// только паспортом. Порча снимка, журнал из базы — паспорта «Кукушки» в нём нет, а данные на её
/// ID перенесены. Копии из папки отдают паспорт обратно (сами они — прежние: данные не
/// вливаются), и вставленная карта возвращает «Кукушке» её лайк; оценка, снятая после порчи, не
/// возвращается.
#[test]
fn the_old_copies_give_back_the_passports_of_tracks_missing_from_the_catalog() {
    // Старая установка: обе песни на месте.
    let (z, c) = (Scratch::new(), Scratch::new());
    let (kino_z, queen_z, copy_z) = {
        let core = Core::open(z.path()).unwrap();
        core.seed_for_test(vec![kino(), queen()]).unwrap();
        let (kino, queen) = (at(&core, KINO), at(&core, QUEEN));
        core.like(kino).unwrap();
        core.rate(queen, Some(stars(5))).unwrap();
        (kino, queen, core.mirror_copy().unwrap())
    };
    // Переустановка: карта с «Кукушкой» не вставлена. Копия возвращает данные обеих песен; Queen
    // узнана по паспорту, «Кукушка» живёт одним паспортом.
    let copy_c = {
        let core = Core::open(c.path()).unwrap();
        core.seed_for_test(vec![queen()]).unwrap();
        core.restore_mirror(vec![dup(&copy_z)]).unwrap();
        assert!(core.user_data(kino_z).unwrap().liked, "the data of the missing track came over");
        assert_eq!(core.user_data(queen_z).unwrap().rating, Some(stars(5)));
        core.mirror_copy().unwrap()
    };
    corrupt(&c);
    let core = Core::open(c.path()).unwrap();
    let report = core.startup_report().unwrap();
    assert!(report.journal_started_over && report.restored_from_journal, "{report:?}");
    assert!(core.user_data(kino_z).unwrap().liked, "the data came over from the database");
    core.rate(queen_z, None).unwrap();
    // Карту вставили, скан нашёл файл: паспорта «Кукушки» в журнале нет — трек не узнан.
    core.seed_for_test(vec![kino()]).unwrap();
    assert!(liked(&core).is_empty(), "nothing links the new file to the old data yet");

    let found = core.inspect_mirror(vec![dup(&copy_z), dup(&copy_c)]).unwrap();
    let restored = core.restore_mirror(vec![dup(&copy_z), dup(&copy_c)]).unwrap();

    assert!(found.news, "the passport that can come back is news: {found:?}");
    assert_eq!(liked(&core), ["Кукушка"], "the passport did not come back");
    assert_eq!(core.user_data(queen_z).unwrap().rating, None, "the rating removed after the damage is back");
    assert_eq!(restored.merged, 1, "only the copy with the passport brings something");
    let again = core.restore_mirror(vec![copy_z, copy_c]).unwrap();
    assert_eq!((again.merged, again.relinked), (0, 0), "the second restore changes nothing");
    assert_eq!(liked(&core), ["Кукушка"]);
}

/// Новое имя установки лежит в файле целым: ни временного файла, ни прежнего имени.
#[test]
fn the_new_name_of_the_installation_is_whole_after_the_journal_started_over() {
    let dir = Scratch::new();
    Core::open(dir.path()).unwrap().like(TrackId::new()).unwrap();
    let before = fs::read_to_string(dir.0.join(DEVICE)).unwrap();
    corrupt(&dir);

    let core = Core::open(dir.path()).unwrap();

    let after = fs::read_to_string(dir.0.join(DEVICE)).unwrap();
    assert_ne!(after, before);
    assert!(after.trim().parse::<DeviceId>().is_ok(), "{after:?}");
    assert!(!dir.0.join(format!("{DEVICE}.tmp")).exists());
    assert!(core.startup_report().unwrap().journal_started_over);
}
