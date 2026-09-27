//! Для тестов этого и других крейтов ядра (фича `testing`): фейковый
//! провайдер, записанные ответы (E1.4) и общий контракт провайдера.

pub mod contract;
mod fake;
mod fixtures;
#[cfg(test)]
mod flawed;

pub use contract::ContractCase;
pub use fake::FakeProvider;
pub use fixtures::FixtureTransport;
