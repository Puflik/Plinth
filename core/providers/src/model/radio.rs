//! Радио от трека (Ю4а, docs/work/yu4a.md): страница подборки провайдера.

use super::{ExternalId, RemoteTrack};

/// Страница радио от трека (Ю4а): треки подборки по порядку и ключ следующей страницы.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RadioPage {
    /// Может быть пустой, и тогда `next` решает, есть ли что-то дальше.
    pub tracks: Vec<RemoteTrack>,
    /// Ключ следующей страницы; `None` — подборка кончилась. Для ядра и приложения строка
    /// непрозрачна, устроена по форме как `ExternalId` (непустая, до 1024 байт, без управляющих).
    pub next: Option<ExternalId>,
}
