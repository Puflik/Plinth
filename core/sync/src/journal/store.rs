//! Хранение журнала на диске (C2.1): снимок и хвост (ADR 0006).
//!
//! - `snapshot` — всё состояние документа (update v2 `yrs`), с номером
//!   последней вошедшей в него операции и контрольной суммой.
//! - `tail` — кадры после снимка, по кадру на операцию: обновление самой
//!   транзакции (update v1). Не дельта по вектору состояния: та тащит все
//!   удаления документа, и лог растёт квадратично (C1).
//!
//! Кадр — длина, CRC32 и байты; после записи — `fsync`. В заголовке хвоста —
//! номер снимка, за которым он идёт.
//!
//! Номер операции (`seq`) растёт на единицу с каждой правкой документа. С
//! идентификатором журнала он образует метку, по которой проекция знает,
//! какое состояние журнала она отражает (C3).
//!
//! # Запись: `Ok` значит «на диске» (Р9б)
//!
//! `persist` отвечает `Ok`, только если правка на диске: кадром в годном хвосте
//! или в снимке. Ошибка — правка осталась только в памяти, и следующая запись
//! кладёт всё целиком. Чего не хватает следующей правке, помнит `Next`:
//!
//! - `Append` — хвост годен, кадр дописывается;
//! - `StartTail` — снимок на диске полный, а хвоста под него нет (сброс после
//!   свёртки не удался, хвост чужой или от прошлого снимка): сначала хвост
//!   начинается заново, затем идёт кадр. Не вышло — правка уходит целым снимком
//!   в этой же записи;
//! - `Snapshot` — в памяти больше, чем на диске (кадр не записался, хвост
//!   испорчен, свёртка не удалась): правка кладётся целым снимком.
//!
//! Состояний три, а не флаг «грязно», ради почти полного телефона: заголовок
//! хвоста и кадр в сотню байт помещаются, снимок в сотни килобайт — нет (рядом
//! лежат и старый, и временный). Пока он не помещается, правки принимаются
//! кадрами, и ни одна не отказывает «через одну».
//!
//! Длинный хвост сворачивается в новый снимок: временный файл, `fsync`,
//! переименование поверх старого, затем сброс хвоста. Снимок записан — `compact`
//! отвечает `Ok`, даже если хвост начать заново не вышло: всё уже в снимке, а
//! хвост начнёт следующая правка. Сбой между переименованием и сбросом оставляет
//! хвост от старого снимка — см. чтение хвоста ниже.
//!
//! # Чтение хвоста (Р9б, Р1.11б)
//!
//! Кадры считаются в `seq` и читаются до первого негодного; `salvaged` — кадры,
//! которые читаются, но в `seq` не считаются.
//!
//! | Хвост на диске | Кадры | Ремонт | Копия рядом |
//! |---|---|---|---|
//! | файла нет; не длиннее заголовка и без годного заголовка (сброс оборвался) | – | нет | нет |
//! | заголовок сошёлся со снимком, все байты — целые кадры | все | нет | нет |
//! | то же, в конце оборванная запись | годные; файл усекается до них | нет | нет |
//! | то же, кадр с неверной суммой или испорченная длина посреди файла | до порчи | да | `tail.damaged-<мс>` |
//! | заголовок цел, журнал другой | – (не вливается: `start_over`, Р1.4) | нет | `tail.foreign-<мс>` |
//! | заголовок не читается или назвал другой номер снимка (в любую сторону); файл длиннее заголовка | `salvaged`: годные кадры за заголовком | да | `tail.damaged-<мс>` |
//!
//! Спасённые кадры применяются к документу вместе с остальными: у каждого своя
//! CRC32, а применение обновления `yrs` к документу, где оно уже есть (хвост
//! прошлого снимка после сбоя при свёртке), ничего не меняет. Если же испорчен
//! заголовок настоящего хвоста, его правки возвращаются, а не пропадают. В `seq`
//! они не считаются: метка журнала не уходит вперёд на кадры, уже вошедшие в
//! снимок. Бит-флип в идентификаторе журнала (байты 6–14 заголовка) отличить от
//! хвоста прежнего журнала нельзя — такой хвост по-прежнему «чужой», его правки
//! не вливаются (копия рядом есть).
//!
//! Кадр, чьи данные не помещаются в файл, — оборванный конец (отрезается молча,
//! места не нужно), если только после его заголовка нет целого кадра, который
//! кончается ровно в конце файла: длина больше нуля, данные помещаются, сумма
//! сходится. Так кончается хвост из целых кадров за кадром с испорченной
//! длиной (Р1.11б): это порча, как неверная сумма. Случайные байты оборванной
//! записи целого кадра ровно в конец не образуют, поэтому сумма считается только
//! там, где кадр приходит ровно в конец, — время линейно по размеру файла, и
//! оборванный кадр импорта в мегабайты отрезается без копии. `SCAN_BUDGET_BYTES`
//! (хеширование за открытие) — страховка от нарочно подобранных байтов: вышел —
//! порча. Известное ограничение: целые кадры за испорченным **и** оборванный
//! конец вместе неотличимы от одного оборванного кадра — отрезается всё с
//! испорченного, а у последнего кадра с испорченной длиной теряется он один.
//!
//! # Открытие не требует места (Р9б)
//!
//! Открытие не отказывает из-за записи: копия рядом, сброс хвоста, усечение
//! оборванного конца не вышли — состояние `StartTail` или `Snapshot`, а ремонт
//! (`Journal::open`) не удался — первая же правка положит всё снимком. Отказывает
//! только чтение: файл не прочесть, снимок не читается, каталог недоступен.
//!
//! Хвост, у которого есть что терять (порча, чужой журнал, кадр, который не
//! обновление), стирается только после копии рядом, и она делается в одном месте
//! — первым делом в `reset_tail`. Копия не удалась — хвост не тронут, и повтор при
//! следующем сбросе.
//!
//! Каталог журнала уходит в Auto Backup целиком (квота 25 МБ на приложение), а
//! отложенные файлы никто не читает, поэтому после каждой копии и при каждом
//! открытии (`prune_aside`) остаются новейшие по метке времени в имени: две
//! `tail.damaged-*`, по одной `snapshot.damaged-*` и `tail.foreign-*`.
//!
//! Снимок, который не читается, `Store` не трогает: открытие — ошибка. Его
//! откладывает рядом как `snapshot.damaged-<мс>` и заменяет новым журналом
//! `start_over` (Р1.4); для него здесь `inspect_snapshot`, `keep_snapshot_aside`
//! и `replace_snapshot`.

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
    next: Next,
    /// Файл `tail` хранит байты, которые нельзя стирать, пока рядом нет их копии: порча
    /// или чужой хвост. Причина — часть имени копии (`tail.<причина>-<мс>`).
    aside: Option<&'static str>,
    #[cfg(test)]
    fail_next_append: bool,
    #[cfg(test)]
    fail_next_aside: bool,
    #[cfg(test)]
    fail_next_tail_header: bool,
}

/// Что нужно следующей правке, чтобы лечь на диск.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Next {
    /// Хвост годен: кадр дописывается.
    Append,
    /// Снимок на диске полный, а хвоста под него нет (сброс не удался, хвост чужой или
    /// от прошлого снимка): сначала хвост начинается заново, затем кадр.
    StartTail,
    /// В памяти больше, чем на диске (отказ записи, порча хвоста): правка кладётся
    /// целым снимком.
    Snapshot,
}

/// Прочитанное с диска: снимок (пустой у нового журнала) и кадры за ним.
pub(crate) struct Loaded {
    pub(crate) snapshot: Vec<u8>,
    /// Кадры хвоста, который идёт за этим снимком: считаются в номере операции.
    pub(crate) frames: Vec<Vec<u8>>,
    /// Кадры хвоста, чей заголовок не сошёлся со снимком (испорчен, чужой номер
    /// снимка): их суммы верны, и они применяются, но в номере операции не считаются.
    /// Уже вошедшие в документ они ничего не меняют: обновления `yrs` идемпотентны.
    pub(crate) salvaged: Vec<Vec<u8>>,
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
        let tail = read_tail(dir, &header)?;
        let mut store = Self {
            dir: dir.to_owned(),
            journal: header.journal,
            seq: header.seq + tail.frames.len() as u64,
            tail: open_tail(dir)?,
            tail_len: tail.len,
            // Хвост отложен как повреждённый: годное из него — только в памяти,
            // первая же запись кладёт всё снимком (`Journal::open`).
            next: if tail.repair { Next::Snapshot } else { Next::Append },
            aside: tail.aside,
            #[cfg(test)]
            fail_next_append: false,
            #[cfg(test)]
            fail_next_aside: false,
            #[cfg(test)]
            fail_next_tail_header: false,
        };
        if tail.len == 0 && !tail.repair {
            // Годного хвоста нет (новый журнал, сброс оборвался, хвост от прошлого снимка
            // или чужой): начать заново. Не вышло — не отказ открытия: снимок полный,
            // хвост начнёт первая же правка.
            if let Err(error) = store.reset_tail(header.seq) {
                log::warn!("journal: the tail was not started anew, the next write retries: {error}");
                store.next = Next::StartTail;
            }
        }
        prune_aside(dir);
        Ok((store, Loaded { snapshot, frames: tail.frames, salvaged: tail.salvaged, repair: tail.repair }))
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
    /// `snapshot` — всё состояние, если придётся писать снимок. `Ok` — правка на диске
    /// (кадром или снимком). Ошибка — правка осталась только в памяти, следующая
    /// запишет всё целиком.
    pub(crate) fn persist(&mut self, update: &[u8], snapshot: impl FnOnce() -> Vec<u8>) -> Result<(), CoreError> {
        let base = self.seq;
        self.seq += 1;
        if self.next == Next::StartTail {
            // Снимок на диске полный до `base`: хвост начинается под него. Не вышло — правка уходит снимком.
            match self.reset_tail(base) {
                Ok(()) => self.next = Next::Append,
                Err(error) => {
                    log::warn!("journal: the tail was not started anew, saving a whole snapshot instead: {error}");
                    self.next = Next::Snapshot;
                }
            }
        }
        if self.next == Next::Snapshot {
            return self.compact(&snapshot());
        }
        if let Err(error) = self.append(update) {
            self.next = Next::Snapshot;
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

    /// Пишет снимок всего состояния и начинает хвост заново. Ошибка — снимок не
    /// записан: на диске всё прежнее, хвост годен. Снимок записан, а хвост начать
    /// заново не вышло (нет места, нет копии рядом) — не ошибка: всё уже в снимке, а
    /// хвост начнёт следующая правка.
    pub(crate) fn compact(&mut self, snapshot: &[u8]) -> Result<(), CoreError> {
        write_snapshot(&self.dir, &Header { journal: self.journal, seq: self.seq }, snapshot)?;
        self.next = match self.reset_tail(self.seq) {
            Ok(()) => Next::Append,
            Err(error) => {
                log::warn!("journal: the snapshot is saved, the tail is not started anew yet: {error}");
                Next::StartTail
            }
        };
        Ok(())
    }

    /// В памяти больше, чем на диске: следующая правка кладётся целым снимком.
    pub(crate) fn mark_unsaved(&mut self) {
        self.next = Next::Snapshot;
    }

    /// Хвост на диске нельзя стирать без копии рядом (кадр, который не удалось
    /// применить): копия делается до сброса хвоста, а правка кладётся снимком.
    pub(crate) fn keep_tail_aside(&mut self, why: &'static str) {
        self.aside.get_or_insert(why);
        self.next = Next::Snapshot;
    }

    #[cfg(test)]
    pub(crate) fn fail_next_append(&mut self) {
        self.fail_next_append = true;
    }

    /// Следующая копия рядом не удастся, как на полном диске.
    #[cfg(test)]
    pub(crate) fn fail_next_aside(&mut self) {
        self.fail_next_aside = true;
    }

    /// Следующий сброс хвоста усечёт файл и не сможет записать заголовок.
    #[cfg(test)]
    pub(crate) fn fail_next_tail_header(&mut self) {
        self.fail_next_tail_header = true;
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

    /// Пустой хвост за снимком `base`. Файл, который нельзя стирать без копии, сначала
    /// копируется рядом: не вышло — хвост остаётся как был.
    fn reset_tail(&mut self, base: u64) -> Result<(), CoreError> {
        if let Some(why) = self.aside {
            self.copy_aside(why)?;
        }
        let path = self.dir.join(TAIL);
        let mut tail = File::create(&path).map_err(|e| io_error("reset tail", &e))?;
        #[cfg(test)]
        if std::mem::take(&mut self.fail_next_tail_header) {
            return Err(CoreError::storage("journal: simulated header write failure"));
        }
        tail.write_all(&header(TAIL_KIND, &Header { journal: self.journal, seq: base }))
            .and_then(|()| tail.sync_all())
            .map_err(|e| io_error("reset tail", &e))?;
        self.tail = open_tail(&self.dir)?;
        self.tail_len = HEADER_LEN as u64;
        Ok(())
    }

    fn copy_aside(&mut self, why: &'static str) -> Result<(), CoreError> {
        #[cfg(test)]
        if std::mem::take(&mut self.fail_next_aside) {
            return Err(CoreError::storage("journal: simulated copy failure"));
        }
        keep_aside(&self.dir, &self.dir.join(TAIL), why)?;
        self.aside = None;
        prune_aside(&self.dir);
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

/// Файл не читается: испорчен или записан более новой версией приложения.
enum Unreadable {
    Damaged(&'static str),
    Newer(u8),
}

impl Unreadable {
    fn into_error(self) -> CoreError {
        match self {
            Self::Damaged(what) => damaged(what),
            Self::Newer(format) => damaged(&format!("journal format {format} is newer than this app ({FORMAT})")),
        }
    }
}

fn check_header(bytes: &[u8], kind: u8) -> Result<Header, Unreadable> {
    if bytes.len() < HEADER_LEN || bytes[..4] != MAGIC || bytes[5] != kind {
        return Err(Unreadable::Damaged("not a journal file"));
    }
    if bytes[4] > FORMAT {
        return Err(Unreadable::Newer(bytes[4]));
    }
    let mut journal = [0_u8; 8];
    journal.copy_from_slice(&bytes[6..14]);
    let mut seq = [0_u8; 8];
    seq.copy_from_slice(&bytes[14..22]);
    Ok(Header { journal: i64::from_le_bytes(journal), seq: u64::from_le_bytes(seq) })
}

fn check_snapshot(bytes: &[u8]) -> Result<(Header, Vec<u8>), Unreadable> {
    let header = check_header(bytes, SNAPSHOT_KIND)?;
    let rest = &bytes[HEADER_LEN..];
    if rest.len() < 4 {
        return Err(Unreadable::Damaged("snapshot is cut short"));
    }
    let (crc, payload) = rest.split_at(4);
    if crc32fast::hash(payload).to_le_bytes() != crc {
        return Err(Unreadable::Damaged("snapshot is damaged"));
    }
    Ok((header, payload.to_vec()))
}

fn read_snapshot(bytes: &[u8]) -> Result<(Header, Vec<u8>), CoreError> {
    check_snapshot(bytes).map_err(Unreadable::into_error)
}

/// Что лежит в файле `snapshot`.
pub(crate) enum Snapshot {
    /// Заголовок и сумма сошлись; данные документа (пусты у нового журнала).
    /// Разберёт ли их `yrs`, здесь не проверено.
    Readable(Vec<u8>),
    /// Записан более новой версией приложения: не испорчен, но и не читается.
    Newer,
    /// Не снимок журнала, обрезан или сумма не сошлась; причина — без путей.
    Damaged(String),
}

/// Файл `snapshot` каталога `dir`; `None` — его (или каталога) нет. Ничего не
/// пишет и не создаёт; ошибка — файл не прочесть.
pub(crate) fn inspect_snapshot(dir: &Path) -> Result<Option<Snapshot>, CoreError> {
    let Some(bytes) = read_file(&dir.join(SNAPSHOT))? else { return Ok(None) };
    Ok(Some(match check_snapshot(&bytes) {
        Ok((_, payload)) => Snapshot::Readable(payload),
        Err(Unreadable::Newer(_)) => Snapshot::Newer,
        Err(Unreadable::Damaged(why)) => Snapshot::Damaged(why.to_owned()),
    }))
}

/// Копия снимка рядом, `snapshot.damaged-<мс>`; оригинал на месте.
pub(crate) fn keep_snapshot_aside(dir: &Path) -> Result<(), CoreError> {
    let aside = dir.join(format!("{SNAPSHOT}.damaged-{}", Timestamp::now().as_millis()));
    fs::copy(dir.join(SNAPSHOT), aside).map(drop).map_err(|e| io_error("keep snapshot aside", &e))?;
    prune_aside(dir);
    Ok(())
}

/// Ставит на место снимка новый журнал: новый идентификатор, номер операции
/// `seq`, состояние `payload` (пусто — как у нового журнала). Одна замена
/// файла: до неё на диске прежний снимок, после — новый.
pub(crate) fn replace_snapshot(dir: &Path, seq: u64, payload: &[u8]) -> Result<(), CoreError> {
    write_snapshot(dir, &Header { journal: new_journal_id(), seq }, payload)
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

/// Что прочитано из хвоста.
struct TailRead {
    frames: Vec<Vec<u8>>,
    salvaged: Vec<Vec<u8>>,
    /// Длина годной части файла; 0 — хвост начинается заново.
    len: u64,
    /// Хвост поврежден: состояние надо сразу свернуть в снимок.
    repair: bool,
    /// Файл надо отложить копией, прежде чем его перезапишут.
    aside: Option<&'static str>,
}

impl TailRead {
    fn empty() -> Self {
        Self { frames: Vec::new(), salvaged: Vec::new(), len: 0, repair: false, aside: None }
    }
}

/// Чем кончается последовательность кадров.
enum Verdict {
    /// Все байты — целые кадры.
    Clean,
    /// Оборванный конец: после целых кадров — начало записи, которой не суждено
    /// закончиться (сбой питания, конец места).
    Torn,
    /// Порча: кадр с неверной суммой или длина, испорченная посреди файла.
    Damaged,
}

struct Scan {
    frames: Vec<Vec<u8>>,
    /// Где кончается годная часть, от начала `body`.
    end: usize,
    verdict: Verdict,
}

/// Кадры из `body` (всё, что за заголовком хвоста) до первого негодного.
fn scan_frames(body: &[u8]) -> Scan {
    let mut frames = Vec::new();
    let mut at = 0;
    while at < body.len() {
        let Some(frame) = body.get(at..at + FRAME_HEADER_LEN) else {
            return Scan { frames, end: at, verdict: Verdict::Torn };
        };
        let length = u32::from_le_bytes([frame[0], frame[1], frame[2], frame[3]]) as usize;
        let data = at + FRAME_HEADER_LEN;
        let Some(payload) = body.get(data..data.saturating_add(length)) else {
            // Данных меньше, чем заявлено: оборванная запись или испорченная длина.
            // Целые кадры вплотную до конца файла — свидетельство порчи (Р1.11б, Р9б).
            let verdict = if whole_frame_follows(&body[data..]) { Verdict::Damaged } else { Verdict::Torn };
            return Scan { frames, end: at, verdict };
        };
        if crc32fast::hash(payload).to_le_bytes() != frame[4..8] {
            return Scan { frames, end: at, verdict: Verdict::Damaged };
        }
        frames.push(payload.to_vec());
        at = data + length;
    }
    Scan { frames, end: at, verdict: Verdict::Clean }
}

/// Кадры хвоста за снимком `snapshot`.
///
/// Заголовок хвоста совпадает со снимком — кадры читаются до первого негодного.
/// Не совпадает (испорчен или назвал другой номер снимка) и за ним есть байты — кадры
/// всё равно читаются, а файл откладывается рядом: у каждого своя сумма, и повторное
/// применение к документу, где они уже есть, ничего не меняет. Чужой журнал —
/// отложен и не вливается. Пустой файл или обрывок заголовка — сброс хвоста оборвался,
/// терять нечего.
fn read_tail(dir: &Path, snapshot: &Header) -> Result<TailRead, CoreError> {
    let path = dir.join(TAIL);
    let Some(bytes) = read_file(&path)? else { return Ok(TailRead::empty()) };
    let trusted = match check_header(&bytes, TAIL_KIND) {
        Ok(header) if header.journal != snapshot.journal => {
            log::warn!("journal: tail belongs to another journal, kept aside");
            return Ok(TailRead { aside: Some("foreign"), ..TailRead::empty() });
        }
        Ok(header) => header.seq == snapshot.seq,
        Err(_) => false,
    };
    if !trusted {
        if bytes.len() <= HEADER_LEN {
            return Ok(TailRead::empty());
        }
        log::warn!("journal: the tail header does not match the snapshot; its frames are read, the file is kept aside");
        let scan = scan_frames(&bytes[HEADER_LEN..]);
        return Ok(TailRead { salvaged: scan.frames, repair: true, aside: Some("damaged"), ..TailRead::empty() });
    }

    let scan = scan_frames(&bytes[HEADER_LEN..]);
    let end = HEADER_LEN + scan.end;
    match scan.verdict {
        Verdict::Damaged => {
            log::warn!("journal: damaged frame {} in the tail, the rest is kept aside", scan.frames.len());
            return Ok(TailRead { frames: scan.frames, repair: true, aside: Some("damaged"), ..TailRead::empty() });
        }
        Verdict::Torn => {
            log::info!("journal: dropped a torn frame at the end of the tail");
            let trimmed = OpenOptions::new()
                .write(true)
                .open(&path)
                .and_then(|file| file.set_len(end as u64).and_then(|()| file.sync_all()));
            if let Err(error) = trimmed {
                log::warn!(
                    "journal: could not drop the torn end of the tail, the next write saves a snapshot: {error}"
                );
                return Ok(TailRead { frames: scan.frames, repair: true, ..TailRead::empty() });
            }
        }
        Verdict::Clean => {}
    }
    Ok(TailRead { frames: scan.frames, len: end as u64, ..TailRead::empty() })
}

/// Сколько данных за одно открытие хеширует поиск целого кадра. Хешируются только
/// кандидаты, чей кадр приходит ровно в конец файла, — у случайных байтов их почти
/// нет; предел нужен против нарочно подобранных.
const SCAN_BUDGET_BYTES: usize = 64 * 1024 * 1024;

/// Кадр хвоста объявил больше данных, чем осталось в файле; `rest` — всё после его
/// заголовка. Порча ли это (длину испортило посреди файла) или оборванная запись
/// (сбой питания, конец места)? Порча, если в `rest` есть целый кадр, который
/// кончается ровно в конце файла: длина больше нуля, данные помещаются, CRC32
/// сходится (так кончается хвост, из целых кадров за испорченным). Оборванный
/// кадр — это начало одной записи, и целого кадра в нём нет; нули после сбоя питания
/// выглядят кадром с суммой 0, а настоящий кадр пустым не бывает. Поиск идёт по
/// длинам без хеширования: сумма считается только у смещения, где кадр приходит ровно
/// в конец, — время линейно по размеру `rest`, как бы ни выглядели его байты. Предел
/// хеширования вышел — тоже порча (данные до кадра целы, цена — копия рядом).
/// Известное ограничение: за испорченным кадром целые кадры и оборванный конец
/// вместе (две беды разом) неотличимы от одного оборванного кадра.
fn whole_frame_follows(rest: &[u8]) -> bool {
    let Some(last) = rest.len().checked_sub(FRAME_HEADER_LEN) else { return false };
    let mut hashed = 0_usize;
    for start in 0..=last {
        if frame_end(rest, start) != Some(rest.len()) {
            continue;
        }
        let data = &rest[start + FRAME_HEADER_LEN..];
        hashed += data.len();
        if hashed > SCAN_BUDGET_BYTES || crc32fast::hash(data).to_le_bytes() == rest[start + 4..start + 8] {
            return true;
        }
    }
    false
}

/// Конец кадра, начинающегося на `at`: длина больше нуля и данные помещаются в `bytes`.
fn frame_end(bytes: &[u8], at: usize) -> Option<usize> {
    let data = at.checked_add(FRAME_HEADER_LEN)?;
    let head = bytes.get(at..data)?;
    let length = u32::from_le_bytes([head[0], head[1], head[2], head[3]]) as usize;
    let end = data.checked_add(length)?;
    (length > 0 && end <= bytes.len()).then_some(end)
}

/// Копия испорченного файла рядом, для отчёта о сбое.
fn keep_aside(dir: &Path, path: &Path, why: &str) -> Result<(), CoreError> {
    let aside = dir.join(format!("{TAIL}.{why}-{}", Timestamp::now().as_millis()));
    fs::copy(path, aside).map(drop).map_err(|e| io_error("keep tail aside", &e))
}

/// Отложенные файлы, которые остаются рядом: имя до метки времени и сколько новейших.
/// Каталог журнала уходит в Auto Backup целиком (квота 25 МБ на приложение), и копии,
/// которых никто не читает, не должны копиться.
const ASIDE_KEPT: [(&str, usize); 3] = [("snapshot.damaged-", 1), ("tail.damaged-", 2), ("tail.foreign-", 1)];

/// Стирает лишние отложенные файлы: остаются новейшие по метке времени в имени.
/// Ошибка стирания — предупреждение: чистка не должна мешать журналу.
fn prune_aside(dir: &Path) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    let names: Vec<String> =
        entries.filter_map(Result::ok).filter_map(|entry| entry.file_name().into_string().ok()).collect();
    for (prefix, kept) in ASIDE_KEPT {
        let mut found: Vec<(u64, &String)> = names
            .iter()
            .filter_map(|name| {
                let stamp = name.strip_prefix(prefix)?;
                // Метка — только цифры и влезает в u64; остальные имена не наши, их не трогаем.
                if stamp.is_empty() || !stamp.bytes().all(|byte| byte.is_ascii_digit()) {
                    return None;
                }
                Some((stamp.parse().ok()?, name))
            })
            .collect();
        found.sort();
        let surplus = found.len().saturating_sub(kept);
        for (_, name) in &found[..surplus] {
            if let Err(error) = fs::remove_file(dir.join(name)) {
                log::warn!("journal: could not remove {name}: {error}");
            }
        }
    }
}

#[cfg(test)]
mod r9b_tests;

#[cfg(test)]
mod r9f_tests;

#[cfg(test)]
mod tests {
    use std::fs::{self, OpenOptions};
    use std::io::Write;
    use std::path::PathBuf;

    use plinth_types::{CoreError, DeviceId};

    use super::{
        COMPACT_TAIL_BYTES, FRAME_HEADER_LEN, HEADER_LEN, SCAN_BUDGET_BYTES, SNAPSHOT, SNAPSHOT_TMP, Store, TAIL,
        prune_aside, whole_frame_follows,
    };

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

    /// Имена файлов каталога, начинающиеся с `prefix`, по порядку.
    fn aside(dir: &Scratch, prefix: &str) -> Vec<PathBuf> {
        let mut found: Vec<PathBuf> = fs::read_dir(&dir.0)
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .filter(|path| path.file_name().unwrap().to_string_lossy().starts_with(prefix))
            .collect();
        found.sort();
        found
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

        let (mut store, loaded) = Store::open(&dir.0).unwrap();

        assert_eq!(loaded.frames, vec![b"one".to_vec()]);
        assert!(loaded.repair);
        assert!(aside(&dir, "tail.damaged-").is_empty(), "открытию копия не нужна: файл пока цел");
        store.compact(b"state").unwrap();
        let copies = aside(&dir, "tail.damaged-");
        assert_eq!(copies.len(), 1, "копия появляется при ремонте, где хвост перезаписывается");
        assert_eq!(fs::read(&copies[0]).unwrap(), bytes, "копия — хвост как он был");
    }

    /// Снимок — всё состояние. Испорченный `Store` не читает и не переписывает:
    /// его откладывает и заменяет `start_over` (Р1.4).
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

    /// Сбой между новым снимком и сбросом хвоста: хвост уже в снимке. Его кадры
    /// читаются как спасённые (применение к документу, где они есть, ничего не
    /// меняет), но в номер операции не входят; файл откладывается рядом при ремонте.
    #[test]
    fn a_tail_of_the_previous_snapshot_is_salvaged_but_not_counted() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        drop(store);
        let stale_tail = fs::read(dir.0.join(TAIL)).unwrap();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.compact(b"state").unwrap();
        drop(store);
        fs::write(dir.0.join(TAIL), &stale_tail).unwrap();

        let (mut store, loaded) = Store::open(&dir.0).unwrap();

        assert!(loaded.frames.is_empty());
        assert_eq!(loaded.salvaged, vec![b"one".to_vec()]);
        assert!(loaded.repair);
        assert_eq!(store.seq(), 1, "спасённый кадр уже в снимке: метка не уходит вперёд");
        assert!(aside(&dir, "tail.damaged-").is_empty(), "копия делается там, где хвост перезаписывается");
        store.compact(b"state").unwrap();
        let copies = aside(&dir, "tail.damaged-");
        assert_eq!(copies.len(), 1);
        assert_eq!(fs::read(&copies[0]).unwrap(), stale_tail);
    }

    /// Испорчен заголовок настоящего хвоста: его кадры не пропадают, а читаются.
    #[test]
    fn a_damaged_tail_header_keeps_its_frames() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        for frame in [b"one".as_slice(), b"two", b"three"] {
            store.persist(frame, Vec::new).unwrap();
        }
        drop(store);
        let tail = dir.0.join(TAIL);
        let mut bytes = fs::read(&tail).unwrap();
        bytes[0] ^= 0xff;
        fs::write(&tail, &bytes).unwrap();

        let (store, loaded) = Store::open(&dir.0).unwrap();

        assert!(loaded.frames.is_empty());
        assert_eq!(loaded.salvaged, vec![b"one".to_vec(), b"two".to_vec(), b"three".to_vec()]);
        assert!(loaded.repair);
        assert_eq!(store.seq(), 0);
        assert_eq!(fs::read(&tail).unwrap(), bytes, "файл на месте, пока нет копии рядом");
    }

    /// Хвост другого журнала не читается и не вливается; он откладывается рядом, а
    /// свой хвост начинается заново сразу.
    #[test]
    fn a_tail_of_another_journal_is_kept_aside_and_not_read() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        drop(store);
        let tail = dir.0.join(TAIL);
        let mut bytes = fs::read(&tail).unwrap();
        bytes[6] ^= 0xff;
        fs::write(&tail, &bytes).unwrap();

        let (store, loaded) = Store::open(&dir.0).unwrap();

        assert!(loaded.frames.is_empty() && loaded.salvaged.is_empty());
        assert!(!loaded.repair);
        assert_eq!(store.seq(), 0);
        let copies = aside(&dir, "tail.foreign-");
        assert_eq!(copies.len(), 1);
        assert_eq!(fs::read(&copies[0]).unwrap(), bytes);
        assert_eq!(fs::metadata(&tail).unwrap().len(), HEADER_LEN as u64, "хвост начат заново");
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

    /// Кадр как он лежит в хвосте: длина, CRC32, данные.
    fn frame(data: &[u8]) -> Vec<u8> {
        let mut bytes = u32::try_from(data.len()).unwrap().to_le_bytes().to_vec();
        bytes.extend_from_slice(&crc32fast::hash(data).to_le_bytes());
        bytes.extend_from_slice(data);
        bytes
    }

    /// Порча длины — целый кадр, который кончается ровно в конце файла (так кончается
    /// хвост из целых кадров за испорченным); нули, мусор, кадр без данных, оборванный
    /// кадр и целый кадр, за которым есть ещё байты, — нет.
    #[test]
    fn a_whole_frame_ending_exactly_at_the_end_of_the_file_is_found_and_nothing_else_is() {
        assert!(whole_frame_follows(&[b"junk".as_slice(), &frame(b"payload")].concat()));
        assert!(whole_frame_follows(&[frame(b"one"), frame(b"two")].concat()));
        assert!(whole_frame_follows(&frame(b"x")));
        assert!(!whole_frame_follows(&[]));
        assert!(!whole_frame_follows(&[0; 64]), "восемь нулей — не кадр: настоящий кадр пустым не бывает");
        assert!(!whole_frame_follows(b"just junk, no frame at all"));
        let mut cut = frame(b"payload");
        cut.pop();
        assert!(!whole_frame_follows(&cut), "кадр без последнего байта данных — оборван");
        let mut spoiled = frame(b"payload");
        spoiled[5] ^= 0xff;
        assert!(!whole_frame_follows(&spoiled), "сумма не сошлась");
        assert!(
            !whole_frame_follows(&[frame(b"payload").as_slice(), b"torn"].concat()),
            "целый кадр, за которым ещё байты, не кончается в конце файла: за испорченным кадром — оборванная запись"
        );
    }

    /// Байты, где кандидат в кадры почти на каждом смещении, цепочки кадров до конца файла не образуют:
    /// это оборванный конец, а не порча, и суммы мегабайтами не считаются.
    #[test]
    fn frame_like_bytes_are_a_torn_end_not_damage() {
        // Четвёрки [0, 0, 0x10, 0]: с каждого кратного четырём смещения «длина» — 1 МБ.
        let bytes = [0_u8, 0, 0x10, 0].repeat(SCAN_BUDGET_BYTES / 4 / 16);

        assert!(!whole_frame_follows(&bytes));
    }

    /// Предел хеширования — страховка от нарочно подобранных байтов: с каждого кратного четырём
    /// смещения «кадр» кончается ровно в конце файла, и сумма у него не сходится. Предел вышел —
    /// порча (копия рядом, данные до кадра целы), а не мегабайты хеширования на каждое открытие.
    #[test]
    fn the_hashing_budget_turns_endless_candidates_into_damage() {
        let size = SCAN_BUDGET_BYTES / 4;
        let mut bytes = vec![0xA5_u8; size];
        for start in (0..size - FRAME_HEADER_LEN).step_by(4) {
            let length = u32::try_from(size - start - FRAME_HEADER_LEN).unwrap();
            bytes[start..start + 4].copy_from_slice(&length.to_le_bytes());
        }

        assert!(whole_frame_follows(&bytes));
    }

    /// Новые отложенные файлы вытесняют старейшие по числу в имени (не по строке: 9 старше 10);
    /// чужие имена, имена без метки и сами файлы журнала не тронуты.
    #[test]
    fn pruning_keeps_the_newest_by_the_number_in_the_name_and_leaves_other_names_alone() {
        let dir = Scratch::new();
        fs::create_dir_all(&dir.0).unwrap();
        let pruned = [
            "tail.damaged-9",
            "tail.damaged-10",
            "tail.damaged-100",
            "tail.foreign-5",
            "tail.foreign-50",
            "snapshot.damaged-1",
            "snapshot.damaged-2",
        ];
        let others = [
            "tail.damaged",
            "tail.damaged-",
            "tail.damaged-abc",
            "tail.damaged-12x",
            "tail.damaged-1-2",
            "tail.damaged-99999999999999999999999",
            "tail.other-1",
            "snapshot.damaged",
            "snapshot",
            "snapshot.tmp",
            "tail",
            "device",
        ];
        for name in pruned.iter().chain(&others) {
            fs::write(dir.0.join(name), name).unwrap();
        }

        prune_aside(&dir.0);

        let mut left: Vec<String> =
            fs::read_dir(&dir.0).unwrap().map(|entry| entry.unwrap().file_name().into_string().unwrap()).collect();
        left.sort();
        let mut expected: Vec<&str> =
            ["tail.damaged-10", "tail.damaged-100", "tail.foreign-50", "snapshot.damaged-2"].into();
        expected.extend(others);
        expected.sort_unstable();
        assert_eq!(left, expected);
    }

    /// Хвост за концом файла не обязан кончаться порчей: длина кадра больше остатка и целого кадра
    /// ровно в конец нет — оборванная запись, отрезается молча; есть — порча, хвост откладывается.
    #[test]
    fn a_frame_longer_than_the_file_is_a_torn_end_unless_a_whole_frame_ends_the_file() {
        let beyond = [(1_u32 << 20).to_le_bytes().as_slice(), &[0; 4]].concat();

        let torn = Scratch::new();
        let (mut store, _) = Store::open(&torn.0).unwrap();
        store.persist(b"kept", Vec::new).unwrap();
        drop(store);
        let length = fs::metadata(torn.0.join(TAIL)).unwrap().len();
        append_raw(&torn, &[beyond.as_slice(), &[7; 100]].concat());
        let (store, loaded) = Store::open(&torn.0).unwrap();
        assert_eq!(loaded.frames, vec![b"kept".to_vec()]);
        assert!(!loaded.repair);
        assert_eq!(store.seq(), 1);
        assert_eq!(fs::metadata(torn.0.join(TAIL)).unwrap().len(), length, "оборванный конец отрезан");
        assert!(aside(&torn, "tail.damaged-").is_empty());

        let damaged = Scratch::new();
        let (mut store, _) = Store::open(&damaged.0).unwrap();
        store.persist(b"kept", Vec::new).unwrap();
        drop(store);
        append_raw(&damaged, &[beyond.as_slice(), &frame(b"later")].concat());
        let (mut store, loaded) = Store::open(&damaged.0).unwrap();
        assert_eq!(loaded.frames, vec![b"kept".to_vec()]);
        assert!(loaded.repair);
        store.compact(b"state").unwrap();
        assert_eq!(aside(&damaged, "tail.damaged-").len(), 1);
    }

    /// Снимок не записать (на его месте каталог): хвост цел и годен, `compact` отвечает ошибкой, а
    /// правка ложится кадром — ей не нужен снимок.
    #[test]
    fn a_snapshot_that_cannot_be_written_leaves_the_tail_usable() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        fs::create_dir(dir.0.join(SNAPSHOT_TMP)).unwrap();

        assert!(store.compact(b"state").is_err());
        store.persist(b"two", Vec::new).unwrap();
        drop(store);

        let (store, loaded) = Store::open(&dir.0).unwrap();
        assert!(loaded.snapshot.is_empty());
        assert_eq!(loaded.frames, vec![b"one".to_vec(), b"two".to_vec()]);
        assert_eq!(store.seq(), 2);
    }

    /// Хвост не начать, а снимок записывается: правка уходит снимком в этой же записи, и следующая
    /// ложится кадром — хвост к тому времени начат заново.
    #[test]
    fn a_tail_that_cannot_be_started_sends_the_edit_as_a_snapshot() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        store.fail_next_tail_header();
        store.compact(b"state").unwrap();

        store.fail_next_tail_header();
        store.persist(b"two", || b"state two".to_vec()).unwrap();
        store.persist(b"three", Vec::new).unwrap();
        drop(store);

        let (store, loaded) = Store::open(&dir.0).unwrap();
        assert_eq!(loaded.snapshot, b"state two".to_vec());
        assert_eq!(loaded.frames, vec![b"three".to_vec()]);
        assert_eq!(store.seq(), 3);
    }

    /// Кадр, который не удалось применить, хвост не стирается без копии: `keep_tail_aside` — копия
    /// делается там, где хвост перезаписывается, а правка ложится целым снимком.
    #[test]
    fn a_tail_kept_aside_is_copied_before_the_whole_snapshot_replaces_it() {
        let dir = Scratch::new();
        let (mut store, _) = Store::open(&dir.0).unwrap();
        store.persist(b"one", Vec::new).unwrap();
        let before = fs::read(dir.0.join(TAIL)).unwrap();

        store.keep_tail_aside("damaged");
        assert!(aside(&dir, "tail.damaged-").is_empty());
        store.persist(b"two", || b"everything".to_vec()).unwrap();
        drop(store);

        let copies = aside(&dir, "tail.damaged-");
        assert_eq!(copies.len(), 1);
        assert_eq!(fs::read(&copies[0]).unwrap(), before);
        let (store, loaded) = Store::open(&dir.0).unwrap();
        assert_eq!(loaded.snapshot, b"everything".to_vec());
        assert!(loaded.frames.is_empty());
        assert_eq!(store.seq(), 2);
    }
}
