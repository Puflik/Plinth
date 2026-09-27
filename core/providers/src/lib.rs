//! Провайдеры контента (E1, plan.md 6, docs/adr/0008-provider-trait.md).
//!
//! - [`Provider`] — что умеет каждый источник: найти, раскрыть в треки, дать
//!   адрес потока;
//! - [`model`] — ответ провайдера, один на всех, и здоровье;
//! - [`Registry`] — провайдеры по порядку, здоровье по исходам вызовов,
//!   переход к следующему источнику при отказе (plan.md 6.4);
//! - [`http`] — политика запросов (User-Agent, таймаут, повторы) поверх
//!   транспорта платформы. Сетевого кода в ядре нет;
//! - [`config`] — всё изменчивое у провайдера (эндпоинты, параметры,
//!   заголовки, имена полей) — в данных, а не в коде (E1.3).
//!
//! Крейт зависит только от `plinth-types`: в каталог ответ переводит ядро.

#![forbid(unsafe_code)]

pub mod config;
pub mod http;
pub mod model;
mod provider;
mod registry;
#[cfg(any(test, feature = "testing"))]
pub mod testing;

pub use provider::Provider;
pub use registry::{ProviderSearch, Registry};
