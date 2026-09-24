//! Общие типы ядра (A1.2). Крейт ни от кого в ядре не зависит: иначе
//! `library` и `providers` начнут тянуть типы друг у друга по кругу.

#![forbid(unsafe_code)]

mod error;

pub use error::CoreError;
