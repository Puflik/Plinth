//! Библиотека: модель (B1), хранилище SQLite (B2) и сканер (D1). Об FFI и
//! Kotlin крейт не знает (ADR 0010).

#![forbid(unsafe_code)]

pub mod db;
pub mod model;
pub mod scan;
pub mod sort;
pub mod text;
