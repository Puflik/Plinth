//! Библиотека: модель (B1), хранилище SQLite (B2), сканер (D1) и файлы
//! плейлистов (D4c). Об FFI и Kotlin крейт не знает (ADR 0010).

#![forbid(unsafe_code)]

pub mod db;
pub mod model;
pub mod playlist;
pub mod scan;
pub mod sort;
pub mod text;
