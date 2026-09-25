//! Ход скана (D1) — для экрана и уведомления. Идёт через FFI колбэком;
//! колбэк отвечает, продолжать ли: так скан останавливается по отмене
//! `WorkManager` или по уходу человека с экрана.

/// Этап скана. Обход не знает, сколько файлов впереди, — только сколько
/// нашёл; чтение тегов и запись знают и то и другое.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScanPhase {
    Walking,
    Reading,
    Writing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ScanProgress {
    pub phase: ScanPhase,
    pub done: u32,
    /// Всего на этапе; у обхода — 0: итог неизвестен.
    pub total: u32,
}

/// Куда отдавать ход скана; `false` — остановиться.
pub type Progress<'a> = &'a mut dyn FnMut(ScanProgress) -> bool;

/// Как часто сообщать о ходе: колбэк в Kotlin на каждом файле из 50 000 —
/// лишняя нагрузка, а человеку хватит и этого.
pub(crate) const REPORT_EVERY: u32 = 50;

pub(crate) fn count(n: usize) -> u32 {
    u32::try_from(n).unwrap_or(u32::MAX)
}
