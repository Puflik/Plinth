//! HTTP для провайдеров (E1.1): политика — в ядре, сеть — у платформы.
//! Записанные ответы для тестов — `testing::FixtureTransport` (E1.4).

mod client;
mod header;
mod transport;
mod url;

pub(crate) use client::http_status;
pub use client::{HttpClient, HttpPolicy};
pub(crate) use header::{is_header_name, is_header_value};
pub use transport::{HttpRequest, HttpResponse, HttpTransport};
pub use url::encode_component;
