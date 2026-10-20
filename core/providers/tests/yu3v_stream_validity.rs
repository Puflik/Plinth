//! Ю3в (`docs/work/yu3v.md`): адрес потока YouTube живёт часы, и плеер не должен держать
//! его дольше. Провайдер называет срок вместе с адресом.
//!
//! `StreamRequest::with_valid_for` и `StreamRequest::valid_for` — новый API (сигнатуры в
//! `docs/work/yu3v.md`): пока их нет, тест не собирается.

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use std::time::Duration;

use plinth_providers::model::StreamRequest;

fn header(name: &str, value: &str) -> (String, String) {
    (name.to_owned(), value.to_owned())
}

fn request() -> StreamRequest {
    StreamRequest::new("https://rr1---sn-test.googlevideo.com/videoplayback?expire=1", vec![header("Range", "bytes=0-")])
        .unwrap()
}

#[test]
fn an_address_does_not_age_unless_the_provider_says_so() {
    assert_eq!(request().valid_for(), None);
    assert_eq!(StreamRequest::new("https://archive.org/download/x/01.flac", Vec::new()).unwrap().valid_for(), None);
}

#[test]
fn the_validity_rides_with_the_address_and_leaves_it_alone() {
    let aged = request().with_valid_for(Duration::from_secs(7_200));

    assert_eq!(aged.valid_for(), Some(Duration::from_secs(7_200)));
    assert_eq!(aged.url(), request().url());
    assert_eq!(aged.headers(), request().headers());
}

#[test]
fn the_later_validity_wins_and_a_copy_keeps_it() {
    let aged = request().with_valid_for(Duration::from_secs(60)).with_valid_for(Duration::from_millis(1_500));

    assert_eq!(aged.clone().valid_for(), Some(Duration::from_millis(1_500)));
}

#[test]
fn requests_that_differ_in_validity_are_different() {
    let short = request().with_valid_for(Duration::from_secs(60));
    let long = request().with_valid_for(Duration::from_secs(3_600));

    assert_ne!(short, long);
    assert_ne!(short, request());
    assert_eq!(short, request().with_valid_for(Duration::from_secs(60)));
}
