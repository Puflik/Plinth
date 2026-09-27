//! Общий контракт на фейковом провайдере — образец для каждого настоящего
//! (E2: Internet Archive на записанных ответах).

#![allow(clippy::unwrap_used, reason = "тест: упавший unwrap и есть упавший тест")]

use plinth_providers::testing::{ContractCase, FakeProvider};
use plinth_types::CoreError;

fn working() -> FakeProvider {
    FakeProvider::sample("fake.test").unwrap()
}

fn offline() -> FakeProvider {
    let fake = working();
    fake.fail_with(Some(CoreError::network("offline")));
    fake
}

fn case() -> ContractCase {
    ContractCase::new("fake", "zzz nothing here", "no-such-item")
}

plinth_providers::provider_contract!(fake, working: working(), offline: offline(), case: case());
