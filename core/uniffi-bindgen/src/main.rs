//! `uniffi-bindgen generate --library <libplinth_ffi.so> --language kotlin --out-dir <dir>`:
//! вызывается из `gradle/rust.gradle.kts`, руками запускать не нужно.

fn main() {
    uniffi::uniffi_bindgen_main()
}
