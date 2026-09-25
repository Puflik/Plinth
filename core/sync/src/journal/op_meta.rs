//! Метаданные операции (C2.2): кто, когда и в какой версии схемы. У `yrs`
//! своей истории «кто и когда» нет (ADR 0006), поэтому журнал кладёт её в
//! каждую запись сам.

use plinth_types::{DeviceId, Timestamp};

/// Версия схемы записей, которую пишет этот код. Запись новее проекция
/// пропускает, но в документе не трогает: её поймёт следующая версия
/// приложения. Новые поля и виды записей — новая версия.
pub const OP_SCHEMA: u8 = 1;

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
        Self { at: Timestamp::now(), device, schema: OP_SCHEMA }
    }
}
