//! Вес «без библиотеки»: разница с прототипами — цена CRDT в APK.

#[unsafe(no_mangle)]
pub extern "C" fn proto_run(events: u32) -> u64 {
    let events = proto_common::workload(events as usize, 42);
    proto_common::reference(&events).plays as u64
}
