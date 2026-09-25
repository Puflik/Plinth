//! B3.4: каждая эталонная база выпущенной версии схемы
//! (`tests/fixtures/db/v<N>.db`) открывается текущим кодом, доходит до
//! последней версии и не теряет данных. Эталоны делает
//! `examples/make_db_fixture.rs`, по одному на версию схемы.

// Весь файл — тесты; clippy.toml разрешает unwrap только внутри #[test].
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::path::{Path, PathBuf};

use plinth_library::db::query::TrackSort;
use plinth_library::db::{Database, IntegrityCheck};

fn fixtures() -> Vec<PathBuf> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/db");
    let mut found: Vec<PathBuf> = std::fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|x| x == "db"))
        .collect();
    found.sort();
    found
}

#[test]
fn corpus_has_a_database_per_schema_version() {
    let versions: Vec<String> =
        fixtures().iter().map(|p| p.file_stem().unwrap().to_string_lossy().into_owned()).collect();

    let latest = Database::open_in_memory().unwrap().schema_version().unwrap();

    let expected: Vec<String> = (1..=latest).map(|v| format!("v{v}")).collect();
    assert_eq!(versions, expected, "run: cargo run -p plinth-library --example make_db_fixture");
}

#[test]
fn every_released_database_opens_and_keeps_its_data() {
    let latest = Database::open_in_memory().unwrap().schema_version().unwrap();
    for fixture in fixtures() {
        let dir = std::env::temp_dir().join(format!(
            "plinth-fixture-{}-{}",
            fixture.file_stem().unwrap().to_string_lossy(),
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let copy = dir.join("plinth.db");
        std::fs::copy(&fixture, &copy).unwrap();

        let opened = Database::open(&copy, IntegrityCheck::Now).unwrap();

        let name = fixture.display();
        assert!(opened.recovery.is_none(), "{name}: treated as corrupt");
        let db = opened.db;
        assert_eq!(db.schema_version().unwrap(), latest, "{name}");
        let tracks = db.track_list(TrackSort::Title, None).unwrap();
        assert_eq!(tracks.len(), 3, "{name}");
        let creep = tracks.iter().find(|t| t.title == "Creep").expect("Creep");
        assert!(creep.liked && creep.play_count == 20, "{name}: user data survived");
        let playlist = db.playlists().unwrap().remove(0);
        let order: Vec<String> = db
            .entries(playlist.id)
            .unwrap()
            .iter()
            .map(|e| tracks.iter().find(|t| t.id == e.track).unwrap().title.clone())
            .collect();
        assert_eq!(order, ["You", "Creep", "How Do You?"], "{name}: playlist order survived");
        drop(db);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
