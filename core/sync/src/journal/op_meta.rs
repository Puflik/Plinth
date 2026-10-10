//! Метаданные операции (C2.2): кто, когда и в какой версии схемы. У `yrs`
//! своей истории «кто и когда» нет (ADR 0006), поэтому журнал кладёт её в
//! каждую запись сам.

use plinth_types::{DeviceId, Timestamp};

/// Версия схемы записей, которую пишет этот код. Запись новее читается по
/// известному началу (`codec.rs`), незнакомый вид записи лежит в корне,
/// которого старый код не читает. Новые поля и виды записей — новая версия:
/// 2 — паспорт трека (C4).
pub const OP_SCHEMA: u8 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OpMeta {
    /// Когда операция записана — по часам устройства-автора.
    pub at: Timestamp,
    /// Установка приложения, которая её записала.
    pub device: DeviceId,
    pub schema: u8,
}

impl OpMeta {
    pub fn now(device: DeviceId) -> Self {
        Self::new(device, Timestamp::now())
    }

    /// Заголовок с заданным временем — тесты и `Journal::record_at`.
    pub fn new(device: DeviceId, at: Timestamp) -> Self {
        Self { at, device, schema: OP_SCHEMA }
    }
}
