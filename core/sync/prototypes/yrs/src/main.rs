//! `cargo run --release --bin bench-yrs` — строки таблицы для BENCH.md.

fn main() {
    for report in proto_common::run_all::<proto_yrs::YrsJournal>() {
        println!("{}", proto_common::row(&report));
    }
    println!("concurrent move, playlist of 5 → {}", proto_common::concurrent_move_len::<proto_yrs::YrsJournal>());
}
