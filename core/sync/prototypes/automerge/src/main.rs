//! `cargo run --release --bin bench-automerge` — строки таблицы для BENCH.md.

fn main() {
    for report in proto_common::run_all::<proto_automerge::AutomergeJournal>() {
        println!("{}", proto_common::row(&report));
    }
    println!(
        "concurrent move, playlist of 5 → {}",
        proto_common::concurrent_move_len::<proto_automerge::AutomergeJournal>()
    );
}
