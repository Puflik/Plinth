//! API ядра для Kotlin (A3.1) — методы [`crate::Core`] по темам. Онлайн-
//! источники (поиск, альбом, каталог при действии, адрес потока) —
//! `online_api` (E3).

mod external_api;
mod journal_api;
mod library_api;
mod mirror_api;
mod online_api;
#[cfg(test)]
mod r1_11a_tests;
#[cfg(test)]
mod r1_11b_tests;
#[cfg(test)]
mod r1_11d_tests;
#[cfg(test)]
mod r1_15_tests;
#[cfg(test)]
mod r1_7_tests;
#[cfg(test)]
mod r9e_tests;
#[cfg(test)]
mod r9f_tests;
mod scan_api;
mod test_api;
#[cfg(test)]
mod yu3g_tests;
#[cfg(test)]
mod yu3v_tests;
#[cfg(test)]
mod yu4a_tests;
#[cfg(test)]
mod yu4g_tests;
