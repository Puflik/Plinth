//! Хранение журнала на диске (C2.1): снимок и хвост (ADR 0006).
//!
//! - `snapshot` — всё состояние документа (update v2 `yrs`), с номером
//!   последней вошедшей в него операции и контрольной суммой.
//! - `tail` — кадры после снимка, по кадру на операцию: обновление самой
//!   транзакции (update v1). Не дельта по вектору состояния: та тащит все
//!   удаления документа, и лог растёт квадратично (C1).
//!
//! Кадр — длина, CRC32 и байты; после записи — `fsync`. Оборванный последний
//! кадр (сбой питания посреди записи) отбрасывается. Кадр с неверной суммой
//! останавливает чтение: всё до него читается, файл откладывается в сторону
//! как `tail.damaged-<мс>`, хвост начинается заново.
//!
//! Длинный хвост сворачивается в новый снимок: временный файл, `fsync`,
//! переименование поверх старого, затем сброс хвоста. У хвоста в заголовке —
//! номер снимка, за которым он идёт; сбой между переименованием и сбросом
//! оставляет хвост от старого снимка, и он не читается второй раз.
//!
//! Номер операции (`seq`) растёт на единицу с каждой правкой документа. С
//! идентификатором журнала он образует метку, по которой проекция знает,
//! какое состояние журнала она отражает (C3).

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use plinth_types::{CoreError, Timestamp};

pub(crate) const SNAPSHOT: &str = "snapshot";
pub(crate) const TAIL: &str = "tail";
const SNAPSHOT_TMP: &str = "snapshot.tmp";

/// Хвост длиннее — сворачивается. Снимок на 10 000 событий весит сотни
/// килобайт (C1), перезапись раз в пару тысяч операций незаметна.
pub(crate) const COMPACT_TAIL_BYTES: u64 = 256 * 1024;

const MAGIC: [u8; 4] = *b"PLNJ";
/// Версия формата файлов журнала. Файл новее — ошибка, как у базы (ADR 0011).
const FORMAT: u8 = 1;
const SNAPSHOT_KIND: u8 = b'S';
const TAIL_KIND: u8 = b'T';
/// Магия, формат, вид, идентификатор журнала, номер операции.
const HEADER_LEN: usize = 4 + 1 + 1 + 8 + 8;
const FRAME_HEADER_LEN: usize = 4 + 4;

pub(crate) struct Store {
    dir: PathBuf,
    journal: i64,
    seq: u64,
    tail: File,
    tail_len: u64,
    /// Последняя правка не легла в хвост: следующая запись — целым снимком.
    dirty: bool,
    #[cfg(test)]
    fail_next_append: bool,
}

/// Прочитанное с диска: снимок (пустой у нового журнала) и кадры за ним.
pub(crate) struct Loaded {
    pub(crate) snapshot: Vec<u8>,
    pub(crate) frames: Vec<Vec<u8>>,
    /// Хвост был повреждён и отложен — состояние надо сразу свернуть в снимок.
    pub(crate) repair: bool,
}

struct Header {
    journal: i64,
    seq: u64,
}

impl Store {
    pub(crate) fn open(dir: &Path) -> Result<(Self, Loaded), CoreError> {
        fs::create_dir_all(dir).map_err(|e| io_error("create journal dir", &e))?;
        let (header, snapshot) = match read_file(&dir.join(SNAPSHOT))? {
            Some(bytes) => read_snapshot(&bytes)?,
            None => {
                let header = Header { journal: new_journal_id(), seq: 0 };
                write_snapshot(dir, &header, &[])?;
                (header, Vec::new())
            }
        };
        let (frames, tail_len, repair) = read_tail(dir, &header)?;
        let mut store = Self {
            dir: dir.to_owned(),
            journal: header.journal,
            seq: header.seq + frames.len() as u64,
            tail: open_tail(dir)?,
            tail_len,
            // Хвост отложен как повреждённый: годное из него — только в
            // памяти, первая же запись кладёт всё снимком (`Journal::open`).
            dirty: repair,
            #[cfg(test)]
            fail_next_append: false,
        };
        if tail_len == 0 && !repair {
            store.reset_tail(header.seq)?;
        }
        Ok((store, Loaded { snapshot, frames, repair }))
    }

    pub(crate) fn journal_id(&self) -> i64 {
        self.journal
    }

    pub(crate) fn seq(&self) -> u64 {
        self.seq
    }

    #[cfg(test)]
    pub(crate) fn tail_len(&self) -> u64 {
        self.tail_len
    }

    /// Сохраняет одну правку документа. `update` — обновление её транзакции,
    /// `snapshot` — всё состояние, если придётся писать снимок. Ошибка —
    /// правка осталась только в памяти, следующая запишет всё целиком.
    pub(crate) fn persist(&mut self, update: &[u8], snapshot: impl FnOnce() -> Vec<u8>) -> Result<(), CoreError> {
        self.seq += 1;
        if self.dirty {
            return self.compact(&snapshot());
        }
        if let Err(error) = self.append(update) {
            self.dirty = true;
            return Err(error);
        }
        if self.tail_len > COMPACT_TAIL_BYTES {
            // Правка уже на диске в хвосте; не свернулся — свернётся в другой раз.
            if let Err(error) = self.compact(&snapshot()) {
                log::warn!("journal: compaction failed: {error}");
            }
        }
        Ok(())
    }

    /// Пишет снимок всего состояния и начинает хвост заново.
    pub(crate) fn compact(&mut self, snapshot: &[u8]) -> Result<(), CoreError> {
        write_snapshot(&self.dir, &Header { journal: self.journal, seq: self.seq }, snapshot)?;
        self.reset_tail(self.seq)?;
        self.dirty = false;
        Ok(())
    }

    #[cfg(test)]
    pub(crate) fn fail_next_append(&mut self) {
        self.fail_next_append = true;
    }

    fn append(&mut self, update: &[u8]) -> Result<(), CoreError> {
        #[cfg(test)]
        if std::mem::take(&mut self.fail_next_append) {
            return Err(CoreError::storage("journal: simulated write failure"));
        }
        let length = u32::try_from(update.len()).map_err(|_| CoreError::storage("journal: update too large"))?;
        let mut frame = Vec::with_capacity(FRAME_HEADER_LEN + update.len());
        frame.extend_from_slice(&length.to_le_bytes());
        frame.extend_from_slice(&crc32fast::hash(update).to_le_bytes());
        frame.extend_from_slice(update);
        self.tail.write_all(&frame).and_then(|()| self.tail.sync_data()).map_err(|e| io_error("append", &e))?;
        self.tail_len += frame.len() as u64;
        Ok(())
    }

    /// Пустой хвост за снимком `base`.
    fn reset_tail(&mut self, base: u64) -> Result<(), CoreError> {
        let path = self.dir.join(TAIL);
        let mut tail = File::create(&path).map_err(|e| io_error("reset tail", &e))?;
        tail.write_all(&header(TAIL_KIND, &Header { journal: self.journal, seq: base }))
            .and_then(|()| tail.sync_all())
            .map_err(|e| io_error("reset tail", &e))?;
        self.tail = open_tail(&self.dir)?;
        self.tail_len = HEADER_LEN as u64;
        Ok(())
    }
}

fn io_error(what: &str, error: &io::Error) -> CoreError {
    CoreError::storage(format!("journal: {what}: {error}"))
}

fn damaged(what: &str) -> CoreError {
    CoreError::storage(format!("journal: {what}"))
}

/// Случайный идентификатор журнала: 62 случайных бита UUIDv7.
fn new_journal_id() -> i64 {
    let (_, random) = uuid::Uuid::now_v7().as_u64_pair();
    i64::from_le_bytes(random.to_le_bytes())
}

fn read_file(path: &Path) -> Result<Option<Vec<u8>>, CoreError> {
    match fs::read(path) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(io_error("read", &error)),
    }
}

fn open_tail(dir: &Path) -> Result<File, CoreError> {
    OpenOptions::new().create(true).append(true).open(dir.join(TAIL)).map_err(|e| io_error("open tail", &e))
}

fn header(kind: u8, header: &Header) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(HEADER_LEN);
    bytes.extend_from_slice(&MAGIC);
    bytes.push(FORMAT);
    bytes.push(kind);
    bytes.extend_from_slice(&header.journal.to_le_bytes());
    bytes.extend_from_slice(&header.seq.to_le_bytes());
    bytes
}

fn parse_header(bytes: &[u8], kind: u8) -> Result<Header, CoreError> {
    if bytes.len() < HEADER_LEN || bytes[..4] != MAGIC || bytes[5] != kind {
        return Err(damaged("not a journal file"));
    }
    if bytes[4] > FORMAT {
        return Err(damaged(&format!("journal format {} is newer than this app ({FORMAT})", bytes[4])));
    }
    let mut journal = [0_u8; 8];
    journal.copy_from_slice(&bytes[6..14]);
    let mut seq = [0_u8; 8];
    seq.copy_from_slice(&bytes[14..22]);
    Ok(Header { journal: i64::from_le_bytes(journal), seq: u64::from_le_bytes(seq) })
}

fn read_snapshot(bytes: &[u8]) -> Result<(Header, Vec<u8>), CoreError> {
    let header = parse_header(bytes, SNAPSHOT_KIND)?;
    let rest = &bytes[HEADER_LEN..];
    if rest.len() < 4 {
        return Err(damaged("snapshot is cut short"));
    }
    let (crc, payload) = rest.split_at(4);
    if crc32fast::hash(payload).to_le_bytes() != crc {
        return Err(damaged("snapshot is damaged"));
    }
    Ok((header, payload.to_vec()))
}

/// Снимок пишется во временный файл и переименовывается поверх старого:
/// в любой момент на диске целый снимок — старый или новый.
fn write_snapshot(dir: &Path, header_fields: &Header, payload: &[u8]) -> Result<(), CoreError> {
    let tmp = dir.join(SNAPSHOT_TMP);
    let mut bytes = header(SNAPSHOT_KIND, header_fields);
    bytes.extend_from_slice(&crc32fast::hash(payload).to_le_bytes());
    bytes.extend_from_slice(payload);
    let written = File::create(&tmp).and_then(|mut file| file.write_all(&bytes).and_then(|()| file.sync_all()));
    written.and_then(|()| fs::rename(&tmp, dir.join(SNAPSHOT))).map_err(|e| io_error("write snapshot", &e))?;
    sync_dir(dir);
    Ok(())
}

/// Переименование переживёт сбой питания, только если записан и каталог.
#[cfg(unix)]
fn sync_dir(dir: &Path) {
    if let Err(error) = File::open(dir).and_then(|d| d.sync_all()) {
        log::warn!("journal: sync dir: {error}");
    }
}

#[cfg(not(unix))]
fn sync_dir(_: &Path) {}

/// Кадры хвоста за снимком `snapshot`, длина годной части и нужен ли ремонт.
fn read_tail(dir: &Path, snapshot: &Header) -> Result<(Vec<Vec<u8>>, u64, bool), CoreError> {
    let path = dir.join(TAIL);
    let Some(bytes) = read_file(&path)? else { return Ok((Vec::new(), 0, false)) };
    let Ok(header) = parse_header(&bytes, TAIL_KIND) else { return Ok((Vec::new(), 0, false)) };
    if header.journal != snapshot.journal {
        log::warn!("journal: tail belongs to another journal, kept aside");
        keep_aside(dir, &path, "foreign")?;
        return Ok((Vec::new(), 0, false));
    }
    if header.seq != snapshot.seq {
        // Хвост от прошлого снимка: сбой между сворачиванием и сбросом хвоста.
        return Ok((Vec::new(), 0, false));
    }

    let mut frames = Vec::new();
    let mut at = HEADER_LEN;
    let mut repair = false;
    while at < bytes.len() {
        let Some(frame) = bytes.get(at..at + FRAME_HEADER_LEN) else { break };
        let length = u32::from_le_bytes([frame[0], frame[1], frame[2], frame[3]]) as usize;
        let Some(payload) = bytes.get(at + FRAME_HEADER_LEN..at + FRAME_HEADER_LEN + length) else { break };
        if crc32fast::hash(payload).to_le_bytes() != frame[4..8] {
            log::warn!("journal: damaged frame {} in the tail, the rest is kept aside", frames.len());
            keep_aside(dir, &path, "damaged")?;
            repair = true;
            break;
        }
        frames.push(payload.to_vec());
        at += FRAME_HEADER_LEN + length;
    }
    if repair {
        return Ok((frames, 0, true));
    }
    if at < bytes.len() {
        log::info!("journal: dropped a torn frame at the end of the tail");
        let file = OpenOptions::new().write(true).open(&path).map_err(|e| io_error("trim tail", &e))?;
        file.set_len(at as u64).and_then(|()| file.sync_all()).map_err(|e| io_error("trim tail", &e))?;
    }
    Ok((frames, at as u64, false))
}

/// Копия испорченного файла рядом, для отчёта о сбое.
fn keep_aside(dir: &Path, path: &Path, why: &str) -> Result<(), CoreError> {
    let aside = dir.join(format!("{TAIL}.{why}-{}", Timestamp::now().as_millis()));
    fs::copy(path, aside).map(drop).map_err(|e| io_error("keep tail aside", &e))
}

#[cfg(test)]
mod tests {
    use std::fs::{self, OpenOptions};
    use std::io::Write;
    use std::path::PathBuf;

    use plinth_types::{CoreError, DeviceId};

    use super::{COMPACT_TAIL_BYTES, SNAPSHOT, Store, TAIL};

    /// Пустой каталог журнала для теста; стирается в `Drop`.
    struct Scratch(PathBuf);

    impl Scratch {
        fn new() -> Self {
            Self(std::env::temp_dir().join(format!("plinth-journal-{}", DeviceId::new())))
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn frames(dir: &Scratch) -> (Store, Vec<Vec<u8>>) {
        let (store, loaded) = Store::open(&dir.0).unwrap();
        (store, loaded.frames)
    }

    fn append_raw(dir: &Scratch, bytes: &[u8]) {
        OpenOptions::new().append(true).open(dir.0.join(TAIL)).unwrap().write_all(bytes).unwrap();
    }

    #[test]
    fn a_new_journal_is_empty_and_keeps_its_id() {
        let dir = Scratch::new();

        let (store, loaded) = Store::open(&dir.0).unwrap();
        let journal = store.journal_id();
        drop(store);
        let (again, _) = Store::open(&dir.0).unwrap();

        assert!(loaded.snapshot.is_empty());
        assert!(loaded.frames.is_empty());
        assert_eq!(again.seq(), 0);
        assert_eq!(again.journal_id(), journal);
    }

    #[test]
    fn frames_survive_a_reopen_in_order() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        for frame in [b"one".as_slice(), b"two", b"three"] {
            store.persist(frame, Vec::new).unwrap();
        }
        drop(store);

        let (store, frames) = frames(&dir);

        assert_eq!(frames, vec![b"one".to_vec(), b"two".to_vec(), b"three".to_vec()]);
        assert_eq!(store.seq(), 3);
    }

    #[test]
    fn compaction_folds_the_tail_into_the_snapshot() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        store.persist(b"two", Vec::new).unwrap();

        store.compact(b"state").unwrap();
        drop(store);
        let (store, loaded) = Store::open(&dir.0).unwrap();

        assert_eq!(loaded.snapshot, b"state".to_vec());
        assert!(loaded.frames.is_empty());
        assert_eq!(store.seq(), 2);
    }

    #[test]
    fn a_long_tail_is_folded_on_its_own() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        let frame = vec![7_u8; 1024];

        for _ in 0..=COMPACT_TAIL_BYTES / 1024 {
            store.persist(&frame, || b"state".to_vec()).unwrap();
        }

        assert!(store.tail_len() < COMPACT_TAIL_BYTES);
        let (_, loaded) = Store::open(&dir.0).unwrap();
        assert_eq!(loaded.snapshot, b"state".to_vec());
    }

    /// Сбой посреди записи кадра: кадр отбрасывается, следующие пишутся за ним.
    #[test]
    fn a_torn_last_frame_is_dropped() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"kept", Vec::new).unwrap();
        drop(store);
        append_raw(&dir, &[9, 0, 0, 0, 1, 2]);

        let (mut store, frames) = frames(&dir);
        assert_eq!(frames, vec![b"kept".to_vec()]);
        assert_eq!(store.seq(), 1);
        store.persist(b"next", Vec::new).unwrap();
        drop(store);

        assert_eq!(self::frames(&dir).1, vec![b"kept".to_vec(), b"next".to_vec()]);
    }

    /// Порча кадра посреди хвоста: читается всё до неё, файл откладывается в
    /// сторону, хвост начинается заново — порча не въедет в документ.
    #[test]
    fn a_damaged_frame_stops_the_tail_and_is_kept_aside() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        for frame in [b"one".as_slice(), b"two", b"three"] {
            store.persist(frame, Vec::new).unwrap();
        }
        drop(store);
        let tail = dir.0.join(TAIL);
        let mut bytes = fs::read(&tail).unwrap();
        let second = bytes.len() - (8 + 5) - (8 + 3) + 8;
        bytes[second] ^= 0xff;
        fs::write(&tail, &bytes).unwrap();

        let (_, loaded) = Store::open(&dir.0).unwrap();

        assert_eq!(loaded.frames, vec![b"one".to_vec()]);
        assert!(loaded.repair);
        let kept_aside = fs::read_dir(&dir.0)
            .unwrap()
            .filter_map(Result::ok)
            .any(|e| e.file_name().to_string_lossy().starts_with("tail.damaged-"));
        assert!(kept_aside);
    }

    /// Снимок — всё состояние. Испорченный не читается и не переписывается:
    /// его заменит только восстановление из зеркала (C4).
    #[test]
    fn a_damaged_snapshot_is_an_error_and_stays_untouched() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.compact(b"precious state").unwrap();
        drop(store);
        let path = dir.0.join(SNAPSHOT);
        let mut bytes = fs::read(&path).unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 0xff;
        fs::write(&path, &bytes).unwrap();

        let result = Store::open(&dir.0);

        assert!(matches!(result, Err(CoreError::Storage { .. })));
        assert_eq!(fs::read(&path).unwrap(), bytes);
    }

    #[test]
    fn a_journal_from_a_newer_app_is_not_opened() {
        let dir = Scratch::new();
        drop(Store::open(&dir.0).unwrap());
        let path = dir.0.join(SNAPSHOT);
        let mut bytes = fs::read(&path).unwrap();
        bytes[4] += 1;
        fs::write(&path, &bytes).unwrap();

        let error = Store::open(&dir.0).err().map(|e| e.to_string()).unwrap_or_default();

        assert!(error.contains("newer"), "{error}");
    }

    /// Сбой между новым снимком и сбросом хвоста: хвост уже в снимке, второй
    /// раз он не читается.
    #[test]
    fn a_tail_already_in_the_snapshot_is_ignored() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        drop(store);
        let stale_tail = fs::read(dir.0.join(TAIL)).unwrap();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.compact(b"state").unwrap();
        drop(store);
        fs::write(dir.0.join(TAIL), stale_tail).unwrap();

        let (store, loaded) = Store::open(&dir.0).unwrap();

        assert!(loaded.frames.is_empty());
        assert_eq!(store.seq(), 1);
    }

    /// Правка не записалась: она остаётся в документе, а следующая запись
    /// кладёт на диск всё состояние целиком.
    #[test]
    fn after_a_failed_write_the_next_one_saves_everything() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        store.fail_next_append();

        assert!(store.persist(b"lost", Vec::new).is_err());
        store.persist(b"three", || b"everything".to_vec()).unwrap();
        drop(store);

        let (store, loaded) = Store::open(&dir.0).unwrap();
        assert_eq!(loaded.snapshot, b"everything".to_vec());
        assert!(loaded.frames.is_empty());
        assert_eq!(store.seq(), 3);
    }
}
