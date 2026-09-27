//! Internet Archive на записанных ответах (E2): общий контракт и разбор
//! настоящих элементов — нетлейбл без исполнителя, пластинка 78 об.,
//! закрытый WAV с открытыми производными, MP3 с длиной в 104 часа.
//! Перезапись ответов — `tools/record_ia_fixtures.py`.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use plinth_providers::model::{ExternalId, RemoteTrack, SearchQuery};
use plinth_providers::testing::{ContractCase, FixtureTransport};
use plinth_providers::{InternetArchive, Provider};
use plinth_types::{Bitrate, Format};

fn recorded() -> FixtureTransport {
    FixtureTransport::recorded(&Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/ia")).unwrap()
}

fn working() -> InternetArchive {
    InternetArchive::new(Arc::new(recorded())).unwrap().with_pause(|_| {})
}

fn offline() -> InternetArchive {
    InternetArchive::new(Arc::new(FixtureTransport::new())).unwrap().with_pause(|_| {})
}

fn case() -> ContractCase {
    ContractCase::new("piano", "zzqx plinth nothing", "plinth-no-such-item-zz")
}

plinth_providers::provider_contract!(internet_archive, working: working(), offline: offline(), case: case());

fn resolve(item: &str) -> Vec<RemoteTrack> {
    working().resolve(&ExternalId::new(item).unwrap()).unwrap()
}

fn sources(track: &RemoteTrack) -> Vec<&str> {
    track.sources.iter().map(|source| source.external.as_str()).collect()
}

#[test]
fn search_finds_items_most_downloaded_first() {
    let found = working().search(&SearchQuery::new("Piano", 10)).unwrap();

    let summary: Vec<(&str, Option<&str>, Option<u16>)> =
        found.iter().map(|r| (r.external.as_str(), r.artist.as_deref(), r.year)).collect();
    assert_eq!(
        summary,
        [
            ("MIXG031", None, Some(2013)),
            ("BeethovenPianoConcertoNo.5emperormoiseivitch", Some("Benno Moiseivitch, piano"), None),
            ("top.09", Some("top-40.org"), Some(2006)),
            ("MNymanSoloPianoOM11", Some("Michael Nyman"), Some(2005)),
            (
                "78_oh-doctor_naomi-brown-and-her-piano-h-pearl_gbia0404476a",
                Some("NAOMI BROWN And Her Piano, H. Pearl"),
                None
            ),
        ]
    );
}

#[test]
fn netlabel_album_is_its_mp3_originals_with_ogg_variants() {
    let tracks = resolve("MIXG031");

    let titles: Vec<(&str, Option<u16>)> = tracks.iter().map(|t| (t.title.as_str(), t.number)).collect();
    assert_eq!(titles, [("Animosity", Some(1)), ("In My Dreams", Some(2))]);
    assert_eq!(tracks[0].artist.as_deref(), Some("Intoxicated Piano"));
    assert_eq!(tracks[0].album.as_deref(), Some("Vice Versa"));
    assert_eq!(
        sources(&tracks[0]),
        ["MIXG031/01_Intoxicated_Piano_-_Animosity.mp3", "MIXG031/01_Intoxicated_Piano_-_Animosity.ogg"]
    );
    assert_eq!(tracks[0].duration, Some(Duration::from_secs_f64(150.77)));
}

#[test]
fn record_78_plays_flac_first_and_names_both_artists() {
    let tracks = resolve("78_oh-doctor_naomi-brown-and-her-piano-h-pearl_gbia0404476a");

    assert_eq!(tracks.len(), 1);
    let track = &tracks[0];
    assert_eq!(track.title, "\"OH DOCTOR\"");
    assert_eq!(track.artist.as_deref(), Some("NAOMI BROWN And Her Piano, H. Pearl"));
    assert_eq!(track.duration, Some(Duration::from_secs_f64(127.45)));
    assert_eq!(
        track.sources.iter().map(|s| (s.format, s.bitrate)).collect::<Vec<_>>(),
        [(Format::Flac, None), (Format::Mp3, Some(Bitrate::kbps(233)))]
    );
}

#[test]
fn private_wav_anchors_its_open_derivatives() {
    let tracks = resolve("MNymanSoloPianoOM11");

    assert_eq!(tracks.len(), 1);
    let track = &tracks[0];
    assert_eq!(track.title, "Michael Nyman performs solo piano works from The Piano at Other Minds 11, 2005");
    assert_eq!(track.external.as_str(), "MNymanSoloPianoOM11/SoloPiano.wav");
    assert_eq!(track.number, None, "track \"0\" is no number");
    assert_eq!(
        sources(track),
        [
            "MNymanSoloPianoOM11/SoloPiano.mp3",
            "MNymanSoloPianoOM11/SoloPiano.ogg",
            "MNymanSoloPianoOM11/SoloPiano_vbr.mp3"
        ],
        "private FLAC and 64 kbps MP3 are not sources"
    );
}

#[test]
fn broken_length_of_an_original_falls_back_to_its_ogg() {
    let tracks = resolve("BeethovenPianoConcertoNo.5emperormoiseivitch");

    assert_eq!(tracks.len(), 2);
    assert_eq!(tracks[0].title, "1. I. Allegro");
    assert_eq!(tracks[0].artist.as_deref(), Some("Benno Moiseivitch, piano"));
    assert_eq!(tracks[0].duration, Some(Duration::from_secs_f64(1209.05)));
}

#[test]
fn stream_address_of_a_recorded_source() {
    let stream = working()
        .stream_url(
            &ExternalId::new(
                "78_oh-doctor_naomi-brown-and-her-piano-h-pearl_gbia0404476a/\"OH DOCTOR\" - NAOMI BROWN And Her Piano - H. Pearl.mp3",
            )
            .unwrap(),
        )
        .unwrap();

    assert_eq!(
        stream.url(),
        "https://archive.org/download/78_oh-doctor_naomi-brown-and-her-piano-h-pearl_gbia0404476a/\
         %22OH%20DOCTOR%22%20-%20NAOMI%20BROWN%20And%20Her%20Piano%20-%20H.%20Pearl.mp3"
    );
}
