# r9d — Перепривязка: две установки, доступность, слитый паспорт, раздача пар

> Файл задачи для исполнителя (Sonnet). Пишет планировщик (Sonnet max); исполнитель
> меняет только раздел «Отчёт исполнителя». Порядок работы — `docs/work/README.md`.

- **Ветка:** `work/r9d` · **Ворктри:** `C:\claude\Plinth-r9d`
- **Основа:** `main` на `049b2a7` · **Коммит тестов:** `8748588` (первый в ветке)
- **Откуда:** Р9г в `tasks.md`; находки №6, 17, 18, 21 в `docs/review/r1-review.md`
  (строки 91–97, 181–187, 189–194, 209–215; «Не проверено» → «Журнал» — про убежавшие часы)

Задача с данными пользователя (журнал, перепривязка): **план до исполнителя читает Opus**.

## Цель

Перепривязка (`core/sync/src/journal/relink.rs`) узнаёт треки журнала в каталоге по паспортам.
Когда задача сделана:

1. **№6.** Две установки, восстановившие копии друг друга, видят данные обеих и сходятся на одном
   треке — с меньшим ID, в каком бы порядке они ни сканировали.
2. **№17.** При равном сходстве данные идут треку, который можно сыграть, а не недоступному дублю.
3. **№18.** Слитый трек теряет паспорт: позже он не забирает дубль песни, прослушивания не двоятся.
4. **№21.** Пары раздаются разом, лучшими вперёд (`TrackPassport::pair_up`, Р9в), а не по очереди
   каждому потерянному паспорту.

## Контекст

Прочитать (только это):

- `docs/review/r1-review.md`: №6 (91–97), №17 (181–187), №18 (189–194), №21 (209–215); «Не проверено → Журнал»
  (строка 274: `fold` и убежавшие часы).
- `core/sync/src/journal/relink.rs` — весь (145 строк), он переписывается.
- `core/library/src/model/passport.rs:33-129` — `Pairing`, `key`, `likeness`, `best_match`, `pair_up` (из Р9в;
  `docs/work/r9c.md:94-160` — зачем он такой).
- `core/library/src/db/repo/passport_repo.rs:12-54` — `track_passports`, `rekey_track`.
- `core/sync/src/journal/doc.rs:236-323` (`write_one`), `647-655` (`remove_slots`) — рядом встанет `Op::Retire`;
  `op.rs`, `apply.rs` — целиком.
- `core/sync/src/journal/passport.rs:104-129` — `referenced`, `tracks_of`; `projection.rs:37-57` —
  `record_and_project_all` (журнал первым, база после).
- Кто зовёт `relink`: `core/sync/src/mirror/mod.rs:68-83` («Восстановить»), `core/ffi/src/api/mirror_api.rs:96-105`
  (после каждого скана), `core/ffi/src/session.rs:121-130` (после пересборки проекции при открытии).
- `core/sync/tests/passports.rs:263-300` — старый тест слияния (ID потерянного меньше); он обязан остаться зелёным.
- `docs/adr/0007-journal-as-source-of-truth.md` — «Статус» (строки 3–9) и абзац «Файл перенесли…» (строки 207–221).

## Решение

### Что не так сейчас

`relink` идёт в два шага. **Шаг 1:** каждый потерянный паспорт журнала (его ID нет в каталоге) по очереди
берёт лучшего среди *свободных* треков каталога — без данных и паспорта в журнале — через `best_match`
(при равном счёте первого по ID), и каталог берёт его ID. **Шаг 2:** оставшиеся паспорта (без вариантов
провайдера) сливаются с *занятым* треком каталога, но **только с тем, чей ID новее потерянного**
(`relink.rs:70-76`): `fold` кладёт данные трека каталога на ID журнала, и каталог берёт этот ID.

- **№21.** Оба шага жадные: паспорт сингла (без альбома) идёт первым, у обеих копий для него счёт 1 — он
  забирает первую по ID, то есть альбомную; паспорту альбома остаётся сингл.
- **№17.** `track_passports()` не знает доступности: при равном счёте побеждает младший ID, а им бывает
  недоступный дубль; лайк ложится на скрытый трек.
- **№18.** `fold` не снимает паспорт трека `from`. После слияния он «потерян» (в каталоге уже ID журнала) и
  позже, на первом же скане с дублем песни, шагом 1 отдаёт дублю свой ID — вместе с прослушиваниями `from`,
  уже скопированными на основной трек: в «Недавнем» песня дважды, счётчики двоятся.
- **№6.** Условие «трек каталога новее потерянного» стоит затем, чтобы слияние не гоняло ID туда и обратно, но
  оно односторонне. У двух установок с общей песней у каждой свой ID каталога и обе заняты; та, у которой ID
  *старше*, ничего не сливает — данные второй остаются на чужом ID, которого в каталоге нет. Итог зависит от
  того, чей скан был раньше. То же при убежавших часах старого телефона: ID — UUIDv7, по времени создания.

### Правило: данные встают на меньший ID

Потерянный `gone` (ID журнала) и занятый `local` (трек каталога) — одна песня. Данные складываются на тот,
чей **ID меньше**:

- меньше у `gone` — как сейчас: данные `local` ложатся на `gone`, каталог берёт ID `gone`;
- меньше у `local` — **новое**: каталог не меняется, данные `gone` ложатся на `local`.

В обоих случаях данные трека с **большим** ID (`from`) встают на меньший (`to`), а паспорт `from` снимается.
ID одинаков на всех установках и не зависит от того, кто смотрит, поэтому две установки, восстановившие копии
друг друга, независимо выбирают одного победителя. Правила «каталог всегда берёт ID журнала» или «каталог всегда
оставляет свой» меняют ID двух установок местами: у одной данные на `idA`, у другой на `idB`, и после следующего
обмена копиями они расходятся (так проверялось на временной реализации, мутации M6, M7 ниже). Спорная оценка:
побеждает оценка трека с большим ID (`from`) — правило прежнее (оценка новой установки перебивает старую), только
теперь одинаковое на обеих.

После слияния `from` нигде не нужен: паспорт снят, данные переложены. Повторный `relink` ничего не находит.
Другая установка, ещё не восстановившая нашу копию, продолжает писать данные на свой старый ID — они появятся у нас,
когда она восстановит нашу копию и сама перейдёт на меньший ID (принятое ограничение).

### Новое

Дословно — то, на что опираются тесты и чего нет в `main`.

```rust
// core/sync/src/journal/op.rs — рядом с Describe
/// Снимает паспорт трека: его данные переложены на другой трек (перепривязка,
/// Р9г), и по этому паспорту больше никого не узнавать. Не действие пользователя —
/// его пишет сам журнал.
Retire { track: TrackId },

// core/sync/src/journal/doc.rs, Roots::write_one — после арма Describe
Op::Retire { track } => {
    let found = open(&self.passports, &mut slots.passports, txn, meta.device);
    remove_slots(found, txn, &track.to_string())
}
// Roots::check (пакеты): арм не нужен, там `_ => {}`.

// core/sync/src/journal/apply.rs
Op::Describe(_) | Op::Retire { .. } => Ok(()),   // паспорт в базу не проецируется

// core/library/src/db/repo/passport_repo.rs, impl Database
/// Треки, которые можно сыграть: хотя бы один источник любой версии не `unavailable`
/// (`Availability::is_playable`). Перепривязка отдаёт данные такому треку раньше
/// недоступного дубля (Р9г, №17).
pub fn playable_tracks(&self) -> Result<HashSet<TrackId>, CoreError>;
// SELECT DISTINCT v.track FROM source s JOIN version v ON v.id = s.version WHERE s.availability <> ?1
// ?1 = Availability::Unavailable.code() (use crate::db::codes::Code; first_id::<TrackId> из db::sql)
```

`Op::Retire` не пишет новой записи — только стирает слоты паспорта (как `Rate { rating: None }` стирает слоты
оценки), поэтому формат журнала не меняется и `OP_SCHEMA` остаётся 2: прежняя версия приложения читает такой
журнал, просто без этих паспортов. Стирается то, что установка видит; слот, которого она не видела
(параллельная запись другой установки), остаётся — как у остальных регистров.

`TrackPassport::best_match` больше никто не зовёт (последний вызывающий — `relink`): **удалить** его из
`core/library/src/model/passport.rs` (вместе с упоминаниями в комментариях), чтобы жадный выбор не вернулся.
`key`, `likeness`, `pair_up` не менять.

### Алгоритм `relink` — дословно

Ниже — вся новая логика `relink.rs` (кроме `online`, который остаётся как есть). Формат — по `rustfmt` проекта.

```rust
use std::collections::{BTreeSet, HashMap, HashSet};

use plinth_library::db::Database;
use plinth_library::model::{OnlineTrack, PlayEvent, PlaylistEntry, Rating, TrackPassport};
use plinth_types::{CoreError, EntityId, Timestamp, TrackId};

use super::passport::referenced;
use super::projection::record_and_project_all;
use super::{Journal, JournalState, Op};

pub fn relink(journal: &mut Journal, db: &Database) -> Result<usize, CoreError> {
    let state = journal.state();
    let catalog = db.track_passports()?;
    let known: BTreeSet<TrackId> = catalog.iter().map(|p| p.track).collect();
    let lost: Vec<TrackPassport> = state.passports.iter().filter(|p| !known.contains(&p.track)).cloned().collect();
    if lost.is_empty() {
        return Ok(0);
    }
    let mut claimed = referenced(&state);
    claimed.extend(state.passports.iter().map(|p| p.track));
    // Парой потерянному бывает только трек с тем же ключом: остальные дальше не идут.
    let keys: HashSet<(String, String)> = lost.iter().map(TrackPassport::key).collect();
    let (mut free, mut held) = (Vec::new(), Vec::new());
    for track in catalog {
        if keys.contains(&track.key()) {
            if claimed.contains(&track.track) { held.push(track) } else { free.push(track) }
        }
    }
    let playable = if free.is_empty() && held.is_empty() { HashSet::new() } else { db.playable_tracks()? };
    let mut found: HashMap<usize, usize> =
        TrackPassport::pair_up(&lost, &free, |index| !playable.contains(&free[index].track))
            .into_iter()
            .map(|pair| (pair.wanted, pair.candidate))
            .collect();
    let now = Timestamp::now();
    let mut unmatched: Vec<TrackPassport> = Vec::new();
    let (moved, restored) = db.in_transaction(|db| {
        let (mut moved, mut restored) = (0, 0);
        for (index, passport) in lost.iter().enumerate() {
            if let Some(candidate) = found.remove(&index) {
                db.rekey_track(free[candidate].track, passport.track)?;
                moved += 1;
            } else if db.restore_online_track(passport.track, &online(passport), now)? {
                restored += 1;
            } else if passport.sources.is_empty() {
                unmatched.push(passport.clone());
            }
        }
        Ok((moved, restored))
    })?;
    // Пары пишутся пачками по `FOLD_CHUNK` операций, пара — целиком в одной пачке: правка на
    // пару заново разбирала бы журнал, а пар у двух установок с общей библиотекой тысячи.
    let by_track = ByTrack::of(&state);
    let (mut ops, mut adopted, mut merged) = (Vec::new(), Vec::new(), 0);
    for pair in TrackPassport::pair_up(&unmatched, &held, |index| !playable.contains(&held[index].track)) {
        let (gone, local) = (unmatched[pair.wanted].track, held[pair.candidate].track);
        // Данные встают на меньший ID: он один на все установки.
        let (from, to) = if gone < local { (local, gone) } else { (gone, local) };
        ops.extend(by_track.fold_ops(from, to));
        ops.push(Op::Retire { track: from });
        if from == local {
            // Меньший ID у потерянного: каталог переходит на него.
            adopted.push((local, gone));
        }
        merged += 1;
        if ops.len() >= FOLD_CHUNK {
            write_folds(journal, db, &mut ops, &mut adopted)?;
        }
    }
    write_folds(journal, db, &mut ops, &mut adopted)?;
    log::info!(
        "relink: {moved} of {} tracks of the journal found in the catalog, {merged} merged with tracks that already had data, {restored} online tracks restored",
        state.passports.len()
    );
    Ok(moved + merged + restored)
}

/// Сколько операций журнала ложится одной правкой при слиянии. `Journal::record_all`
/// растёт от размера пачки квадратом: 12 000 прослушиваний одной правкой — 5,3 с (debug),
/// пачками по 500 — 0,23 с.
const FOLD_CHUNK: usize = 500;

/// Пишет накопленные операции слияния одной правкой журнала, затем переводит каталог на
/// ID потерянных (`adopted`: трек каталога, ID журнала). Сначала журнал, потом каталог:
/// оборванный повтор доделает перепривязка — трек без данных и паспорта свободен.
fn write_folds(
    journal: &mut Journal,
    db: &Database,
    ops: &mut Vec<Op>,
    adopted: &mut Vec<(TrackId, TrackId)>,
) -> Result<(), CoreError> {
    if ops.is_empty() {
        return Ok(());
    }
    record_and_project_all(journal, db, ops)?;
    db.in_transaction(|db| adopted.iter().try_for_each(|&(local, gone)| db.rekey_track(local, gone)))?;
    ops.clear();
    adopted.clear();
    Ok(())
}

/// Данные журнала по трекам: слияние берёт данные двух треков, а не разбирает
/// журнал заново на каждую пару.
struct ByTrack<'a> {
    likes: HashSet<TrackId>,
    ratings: HashMap<TrackId, Rating>,
    entries: HashMap<TrackId, Vec<&'a PlaylistEntry>>,
    plays: HashMap<TrackId, Vec<&'a PlayEvent>>,
}

impl<'a> ByTrack<'a> {
    fn of(state: &'a JournalState) -> Self {
        let mut entries: HashMap<TrackId, Vec<&PlaylistEntry>> = HashMap::new();
        for entry in &state.entries {
            entries.entry(entry.track).or_default().push(entry);
        }
        let mut plays: HashMap<TrackId, Vec<&PlayEvent>> = HashMap::new();
        for play in &state.plays {
            plays.entry(play.track).or_default().push(play);
        }
        Self {
            likes: state.likes.iter().copied().collect(),
            ratings: state.ratings.iter().copied().collect(),
            entries,
            plays,
        }
    }

    /// Операции, которые кладут данные трека `from` на трек `to` (лайк объединяется,
    /// оценка и плейлисты `from` побеждают, прослушивания копируются) и снимают их с
    /// `from`. Записи, которые заводятся на `to`, получают идентификатор от прежней и
    /// `to` ([`derived`]). Повтор ничего не задвоит: прослушивание и запись плейлиста
    /// с теми же признаками на `to` не добавляются второй раз.
    fn fold_ops(&self, from: TrackId, to: TrackId) -> Vec<Op> {
        let mut ops = Vec::new();
        if self.likes.contains(&from) {
            ops.push(Op::Like { track: to });
            ops.push(Op::Unlike { track: from });
        }
        if let Some(rating) = self.ratings.get(&from) {
            ops.push(Op::Rate { track: to, rating: Some(*rating) });
            ops.push(Op::Rate { track: from, rating: None });
        }
        let on_to: &[&PlaylistEntry] = self.entries.get(&to).map_or(&[], Vec::as_slice);
        for entry in self.entries.get(&from).into_iter().flatten() {
            if !on_to.iter().any(|other| other.playlist == entry.playlist) {
                ops.push(Op::AddEntry(PlaylistEntry { id: derived(entry.id, to), track: to, ..(*entry).clone() }));
            }
            ops.push(Op::RemoveEntry { entry: entry.id });
        }
        let on_to: &[&PlayEvent] = self.plays.get(&to).map_or(&[], Vec::as_slice);
        for play in self.plays.get(&from).into_iter().flatten() {
            let there =
                on_to.iter().any(|other| other.started_at == play.started_at && other.listened == play.listened);
            if !there {
                ops.push(Op::Play(PlayEvent { id: derived(play.id, to), track: to, ..**play }));
            }
        }
        ops
    }
}

/// Идентификатор записи, которую перепривязка заводит на треке `to` вместо
/// записи `id`. Зависит только от `id` и `to`: две установки, переложившие одни
/// и те же данные порознь (каждая восстановила копию другой), заведут одну и ту
/// же запись, а не две.
fn derived<I: EntityId>(id: I, to: TrackId) -> I {
    let mut bytes = *id.as_bytes();
    for (byte, mask) in bytes.iter_mut().zip(to.as_bytes()) {
        *byte ^= mask;
    }
    // Версия 7 и вариант RFC 9562, как у всех идентификаторов.
    bytes[6] = (bytes[6] & 0x0f) | 0x70;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    I::from_bytes(bytes)
}
```

Шапку модуля (`//!`) переписать под два шага и новое правило (в реализации планировщика она есть;
содержание — «Два шага» ниже). Комментарии оставить на русском, как в окружающем коде.

Что здесь решено и почему:

- **Порядок шагов прежний:** сначала свободные (данных двигать не надо — каталог просто берёт ID), потом занятые.
  Один общий `pair_up` по свободным и занятым вместе поменял бы поведение: потерянный паспорт сел бы на занятый
  трек с лучшим счётом, хотя рядом есть свободный.
- **Ранг — `!playable`** (`false < true`: играемые первыми), дальше `pair_up` сам берёт кандидата с меньшим индексом,
  то есть меньшим ID (`track_passports()` отдаёт по `ORDER BY t.id`, а `free`/`held` сохраняют этот порядок).
  Недоступность — **не фильтр**: единственная копия на вынутой карте всё равно берёт данные, и они появятся, когда
  карта вернётся; недоступная копия, похожая сильнее, побеждает играемую, похожую слабее (тесты защищают оба).
- **Идентификаторы заведённых записей** (`derived`): `fold_ops` заводит на `to` запись плейлиста и прослушивание
  вместо прежних. С новым случайным ID две установки, переложившие порознь одни и те же данные (каждая
  восстановила копию другой, пока та не успела восстановить свою), заведут по своей записи, и после следующего
  обмена копиями на меньшем ID окажутся прослушивания и записи плейлистов дважды. XOR идентификатора записи с
  идентификатором трека — взаимно-однозначен при фиксированном `to` (разные записи не сливаются), а биты версии и
  варианта возвращаются на место. Тест `independent_moves_of_the_same_data_do_not_double_it` держит это.
- **`ByTrack`:** `fold_ops` прежний заново проходил `JournalState` на каждую пару (и `journal.state()` на каждую
  пару) — при тысячах общих песен у двух установок это минуты. Индекс по трекам строится один раз.
- **`FOLD_CHUNK = 500`:** по замеру планировщика `Journal::record_all` растёт от размера пачки квадратом (12 000
  прослушиваний одной правкой — 5,3 с в debug, пачками по 500 — 0,23 с). Пара не рвётся между пачками: её
  операции и `Retire` лежат вместе; оборванный на середине `relink` следующий раз доделает остальные.
- **Журнал, потом каталог** (`write_folds`): если после записи журнала `rekey_track` не вышел, трек каталога остаётся
  без данных и без паспорта, то есть *свободным*, а потерянный паспорт — потерянным; следующий `relink` свяжет
  их шагом 1.
- **Пары, для которых нет операций** (у `from` нет ни лайка, ни оценки, ни записей, ни прослушиваний): всё равно
  `Retire` — паспорт без данных больше не нужен, а трек каталога при меньшем ID потерянного всё равно переходит
  на его ID, как сейчас.
- Внутри `restore` и после скана вызовы прежние: сигнатура `relink` и смысл возвращаемого числа (переведённые +
  слитые + восстановленные сетевые) не меняются; Kotlin и FFI не затронуты.

### Отвергнутые варианты

- **Оставить как есть и ждать второго обмена** (слияние только с более новым ID). Данные второй установки
  появляются, лишь когда она сама восстановит нашу копию и запишет свою заново; если вторая — старый телефон,
  которого больше нет, они не появятся никогда. Находка №6 как раз об этом.
- **Всегда ID каталога** (данные `gone` на `local`, каталог не меняется) или **всегда ID журнала** (как до Р9г
  для новых). Обе пары установок меняются ID местами; данные расходятся (мутации M6, M7).
- **Склейка решением `MergeDecision`** вместо переноса: это v0.3, эпик A (идентичность); проекция решений пока не
  читается.
- **Включить недоступность в `likeness` или фильтровать недоступных:** `likeness` общий со сканом; фильтр лишает
  данных единственную копию на вынутой карте.
- **UUIDv5 для идентификаторов записей:** нужна фича `v5` крейта `uuid` → новая зависимость в `Cargo.lock`;
  XOR даёт то же без неё.
- **Переносить решения о склейке и блокировки треков в `fold_ops`:** см. «Принятые ограничения».

### Принятые ограничения

1. **Решения о склейке (`decisions`) и блокировки треков (`blocklist`) не переносятся** `fold_ops` — как и раньше.
   Теперь, когда паспорт `from` снимается, такие данные на слитом треке остаются без пути назад (раньше паспорт
   оставался и мог ждать свободного трека). Пишет их пока только `start_over`: ни один экран и ни одна операция
   приложения решений и блокировок треков не создаёт. Когда появятся (v0.3, идентичность) — переносить в
   `fold_ops` (идентификатор решения — `derived`, пара `TrackPair::new(to, другой)`).
2. **Новые данные ещё не восстановившей установки** на её старом ID у нас не видны (паспорт снят), пока она не
   восстановит нашу копию. Раньше они были не видны вовсе.
3. **Параллельная запись побеждает снятие:** если другая установка после нашего слияния опишет `from` заново
   (новый слот паспорта), паспорт вернётся, и следующий `relink` слоит снова (`fold_ops` идемпотентен).
4. **Оценка при споре** выбирается по ID, а не по времени записи — как и было (в `JournalState` у оценки времени нет).
5. **Запись плейлиста, которого в журнале нет** (удалён параллельно): `AddEntry` отказывает и откатывает всю пачку;
   `relink` вернёт ошибку — было и раньше (см. «Замечено, не в этой задаче»).

## Инварианты

1. **Один трек — один ID на всех установках:** после обмена копиями песня с данными на обеих лежит на меньшем из
   двух ID у обеих; повторный обмен ничего не меняет (`relink` возвращает 0, ID и данные те же).
2. **Данные не теряются и не двоятся:** лайк, оценка, записи плейлистов, прослушивания `from` оказываются на `to`
   ровно один раз — и когда слияние сделала одна установка, и когда обе независимо (идентификаторы заведённых
   записей выводятся из прежней и ID трека).
3. **Слитый трек не возвращается:** паспорт `from` снимается в той же правке журнала, что и перенос; позже он не
   забирает дубль и его прослушивания.
4. **Свободные — раньше занятых; недоступность — только ранг при равном счёте.**
5. **Журнал первым, каталог вторым;** любой оборванный шаг доделывает следующий `relink` (свободный трек без
   данных и паспорта + потерянный паспорт → шаг 1). Пара — в одной правке журнала.
6. **Нечего перепривязывать — дёшево:** `relink` без потерянных паспортов делает ровно то, что делает сейчас
   (`journal.state()` и `track_passports()`), без новых запросов: он зовётся после каждого скана.
7. **Формат журнала не меняется:** `Op::Retire` только стирает слоты паспорта; прежняя версия приложения читает
   такой журнал.

## Файлы

- **Менять:**
  - `core/sync/src/journal/relink.rs` — по «Решению»; внутренние тесты (ниже) — в `#[cfg(test)] mod tests` в конце файла.
  - `core/sync/src/journal/op.rs` — `Op::Retire`.
  - `core/sync/src/journal/doc.rs` — арм `Op::Retire` в `write_one`; внутренние тесты `Retire` в `mod tests`.
  - `core/sync/src/journal/apply.rs` — арм.
  - `core/library/src/db/repo/passport_repo.rs` — `playable_tracks` и его тест в `mod tests`.
  - `core/library/src/model/passport.rs` — удалить `best_match`.
  - `docs/adr/0007-journal-as-source-of-truth.md` — «Статус» и абзац о перепривязке (ниже).
- **Не трогать:** `core/library/src/scan/*` (скан и `pair_up` — Р9в), `core/sync/src/journal/{passport,projection,rebuild,
  start_over,store,codec,mod}.rs`, `core/ffi`, `app/`, `core/*/Cargo.toml`, `Cargo.lock`, `CHANGELOG.md`, `tasks*.md`,
  `docs/decisions.md`, `plan.md`, `docs/plan/*`, `CLAUDE.md`. Если компилятор требует арм `Op::Retire` где-то ещё —
  стоп и вопрос: планировщик собрал ветку и такого места не нашёл.
- **Защищённые тесты** (из коммита `8748588`; не менять, не удалять, не глушить):
  - `core/sync/tests/r9d_support/mod.rs`
  - `core/sync/tests/r9d_two_installations.rs`
  - `core/sync/tests/r9d_merged_passport.rs`
  - `core/sync/tests/r9d_playable_first.rs`
  - `core/sync/tests/r9d_joint_pairing.rs`

## Тесты (написаны в коммите тестов)

Все собираются на `main` (новый API задействован только через `relink`). «Красный» — падает на `main` утверждением.
Установки в тестах создаются одна за другой, поэтому ID более поздней новее (`assert!(a < b)` в тестах это
проверяет). «Телефон» сканировал раньше «планшета».

`r9d_support/mod.rs` — помощники: установка (папка журнала и каталог в памяти), копия журнала, «Восстановить»,
чтения для утверждений (`liked_titles`, `rating`, `plays_of`, `tracks_in`), `make_unavailable`.

**`r9d_two_installations.rs`** — №6, все четыре красные:

| Тест | Сценарий → ожидание | Почему красный на `main` |
|---|---|---|
| `each_installation_sees_what_the_other_wrote` | сценарий ревью: на телефоне лайк «Song», на планшете 5★ «Song» и лайк «Other»; взаимные «Восстановить» → у обеих «Любимое» = [Other, Song], оценка 5★; «Song» — один и тот же трек, с меньшим ID (телефона); «Other» — тоже один | у телефона оценка `None`: ID планшета новее, слияния нет |
| `a_second_exchange_changes_nothing` | после первого обмена установки видят одно и то же; второй обмен: `relinked == [0, 0]`, ID, лайки, оценка и число прослушиваний те же | после первого обмена установки видят разное |
| `a_disputed_rating_ends_the_same_on_both` | телефон поставил 3★, планшет 5★ → у обеих 5★ (оценка трека с большим ID), и после второго обмена тоже | у телефона остаётся 3★ |
| `independent_moves_of_the_same_data_do_not_double_it` | у каждой установки плейлист с «Song» и прослушивания (2 и 3); обмен → у обеих 5 прослушиваний, «Road» и «Gym» по одной записи; обмен ещё раз → ничего не задвоилось (прослушивания, записи, `play_count == 5`) | после первого обмена у телефона 2 прослушивания из 5. **Держит `derived`:** на мутации M3 (случайные ID) падает после второго обмена |

**`r9d_merged_passport.rs`** — №18, оба красные:

| Тест | Сценарий → ожидание | Почему красный |
|---|---|---|
| `a_merged_track_does_not_come_back_as_a_duplicate` | переустановка: песню сыграли до перепривязки, `restore` сложил данные на ID журнала (меньший) → паспорт слитого трека снят (`journal.passport(fresh) == None`); появился дубль-файл, скан, `relink()` == 0; дубль со своим ID, без прослушиваний, в «Недавнем» песня один раз | паспорт слитого трека остаётся; дубль забирает его |
| `a_track_folded_onto_a_local_one_does_not_come_back_as_a_duplicate` | то же, когда ID потерянного больше (данные встают на трек каталога) | на `main` слияния нет вовсе: у основного 0 прослушиваний вместо 2 |

**`r9d_playable_first.rs`** — №17, два красных, два охранных (зелёные на `main` и обязаны остаться):

| Тест | Сценарий → ожидание | Красный? |
|---|---|---|
| `the_data_goes_to_the_copy_that_plays` | две копии «Song», файл первой (меньший ID) недоступен → ID журнала берёт вторая, лайк виден в «Любимом», оценка на ней | красный: первая по ID недоступная |
| `the_busy_copy_that_plays_is_preferred_too` | то же, но на обеих копиях уже есть лайки этой установки (шаг 2) | красный |
| `only_a_tie_goes_to_the_playable_copy` | недоступная копия с тем же альбомом (счёт 3) против играемой без альбома (счёт 1): берёт недоступная, хотя ID у неё больше | охранный (ранг — не фильтр) |
| `the_only_copy_takes_the_data_even_when_gone` | единственная копия недоступна → всё равно берёт ID журнала; лайк виден, когда файл вернулся | охранный |

**`r9d_joint_pairing.rs`** — №21, два красных, один охранный:

| Тест | Сценарий → ожидание | Красный? |
|---|---|---|
| `the_album_file_keeps_the_album_data` | сценарий ревью: сингл (без альбома, 398,0 с, лайк) и альбомный трек (398,5 с, 5★, плейлист); в новом каталоге альбомный файл просканирован раньше → каждый файл на своём треке | красный: сингл шёл первым и забирал альбомный файл |
| `the_scan_order_does_not_matter` | то же, сингл просканирован раньше | охранный |
| `busy_copies_are_paired_jointly_too` | то же для шага 2: на обоих файлах лайки этой установки | красный |

Исполнитель добавляет **свои тесты** (не защищены), в тех же файлах-исходниках:

- `doc.rs`, `mod tests` (есть помощник `Replica`: `sync`, `fork`): `Retire` стирает паспорт (`state().passports` пуст,
  `write` вернул `true`); `Retire` трека без паспорта — `false`, документ не меняется; параллельный `Describe` другой
  реплики после `Retire` не пропадает (добавление побеждает удаление, как у остальных регистров); старый слот,
  принесённый репликой, не видевшей `Retire`, не воскрешает паспорт после слияния в сторону стёршей.
- `passport_repo.rs`, `mod tests`: `playable_tracks` — трек с доступным источником, с `degraded`, с двумя источниками
  (один `unavailable`), только с `unavailable`, без источников; результат = ровно играемые.
- `relink.rs`, `mod tests`: `derived` — детерминирован, не меняется при повторе, различает записи и различает `to`,
  сохраняет версию 7 и вариант; `ByTrack::fold_ops` — лайк, оценка, записи плейлистов, прослушивания переносятся на `to` и
  снимаются с `from`; запись плейлиста, который у `to` уже есть, не добавляется (`from`-запись снимается);
  прослушивание с теми же `started_at` и `listened` на `to` не копируется; две записи `from` в одном плейлисте обе
  переезжают; пустой `from` даёт пустой список.

## Проверки

```text
cd core && cargo test -p plinth-sync -p plinth-library -p plinth-ffi && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all -- --check
```

На Windows `cargo fmt --check` шумит «Incorrect newline style» по чужим файлам — смотреть только настоящие `Diff in`.
Сборка быстрее, если взять общую папку: `$env:CARGO_TARGET_DIR = "C:\claude\Plinth\core\target"` (так собирал планировщик).
Gradle не нужен: Kotlin и привязки не затронуты.

**Замер** (в ветку не входит). Положить в `core/sync/tests/r9d_scale_tmp.rs` скрипт ниже, прогнать
`cargo test -p plinth-sync --test r9d_scale_tmp -- --nocapture`, числа вписать в отчёт, файл удалить:

```rust
//! ВРЕМЕННЫЙ замер (не входит в ветку): две установки с общей библиотекой, на каждой песне лайк и три
//! прослушивания с обеих сторон; взаимные «Восстановить».

#![allow(clippy::unwrap_used, reason = "замер")]

mod common;
mod r9d_support;

use std::time::Instant;

use common::{Song, play};
use plinth_sync::journal::{Op, record_and_project_all};
use r9d_support::{Installation, PLAYED};

fn songs(n: usize) -> Vec<Song> {
    (0..n)
        .map(|i| {
            let path: &'static str = Box::leak(format!("/storage/emulated/0/Music/Band/Song{i}.mp3").into_boxed_str());
            let title: &'static str = Box::leak(format!("Song {i}").into_boxed_str());
            Song { path, title, artist: "Band", album: Some("Album"), duration_ms: Some(180_000 + i as u64) }
        })
        .collect()
}

fn fill(install: &mut Installation, songs: &[Song], shift: i64) {
    let files = install.db.known_local_files().unwrap();
    let mut ops = Vec::new();
    for (i, song) in songs.iter().enumerate() {
        let track = files[song.path].track;
        ops.push(Op::Like { track });
        for k in 0..3 {
            ops.push(Op::Play(play(track, PLAYED + shift + (i as i64 * 10 + k) * 1_000, 300, Some(180))));
        }
    }
    record_and_project_all(&mut install.journal, &install.db, &ops).unwrap();
}

#[test]
fn measure() {
    for n in [500, 1000, 2000] {
        let songs = songs(n);
        let mut a = Installation::scanned(&songs);
        let mut b = Installation::scanned(&songs);
        fill(&mut a, &songs, 0);
        fill(&mut b, &songs, 500_000_000);
        let (copy_a, copy_b) = (a.copy(), b.copy());
        let mut phases = Vec::new();
        for (name, install, copy) in [("A restores B", &mut a, &copy_b), ("B restores A", &mut b, &copy_a)] {
            let snapshot = plinth_sync::mirror::decode(copy).unwrap().snapshot.to_vec();
            let started = Instant::now();
            install.journal.merge_snapshot(&snapshot).unwrap();
            let merge = started.elapsed();
            let started = Instant::now();
            plinth_sync::journal::catch_up(&install.journal, &install.db).unwrap();
            let catch_up = started.elapsed();
            let started = Instant::now();
            let relinked = install.relink();
            let relink = started.elapsed();
            phases.push(format!("{name}: merge {merge:?}, catch_up {catch_up:?}, relink {relink:?} ({relinked})"));
        }
        println!("n={n}: {}", phases.join("; "));
    }
}
```

Числа планировщика (debug, настольный процессор, одна из двух установок; вторая — те же): `relink` на 500 парах —
0,15 с, на 1000 — 0,36 с, на 2000 — 1,0 с (`merge` и `catch_up` — 20/28 мс, 34/54 мс, 58/114 мс, они не мои). Растёт
чуть быстрее линейного (причину не выясняли), но не квадратом: одной пачкой те же 2000 пар занимали 2,3 с, а 12 000
прослушиваний без деления — 5 с. Шесть раз хуже — стоп и вопрос.

## Готово, когда

- Все защищённые тесты зелёные; весь `cargo test -p plinth-sync -p plinth-library -p plinth-ffi` зелёный; clippy и fmt чистые.
- Внутренние тесты (список выше) написаны; `grep -rn best_match core` пуст.
- ADR 0007: в «Статусе» строка `2026-10-18 (Р9г): …` (дата — день слияния; если не знаешь её, поставь дату сегодняшнего
  дня из окружения) и абзац в «Последствиях» после абзаца о переносе файла — смысл: перепривязка в два шага; пары
  разом, лучшими вперёд, при равенстве — играемый первым; данные двух треков складываются на меньший ID, поэтому две
  установки, восстановившие копии друг друга, сходятся на одном; слитый трек теряет паспорт (`Op::Retire` стирает слоты,
  формат не меняется); заведённые записи получают идентификатор от прежней и ID трека; решения о склейке и блокировки
  трека при слиянии не переносятся (пока нет тех, кто их пишет).
- Замер выполнен, числа в отчёте: в пределах ×2 от чисел планировщика.
- Две мутации проверены и записаны в отчёт (правка одной-двух строк, запуск `r9d_*`, откат): (M2) убрать
  `ops.push(Op::Retire { track: from });` — падают оба теста `r9d_merged_passport`; (M3) заменить `derived(play.id, to)`
  на `plinth_types::PlayEventId::new()` и `derived(entry.id, to)` на `plinth_types::PlaylistEntryId::new()` — падает
  `independent_moves_of_the_same_data_do_not_double_it`.

## Остановиться и написать, если

- защищённый тест кажется неверным;
- для решения нужно менять файл вне списка «Менять» (в том числе если арм `Op::Retire` нужен ещё где-то);
- проверки упали дважды подряд (одна попытка исправить, потом стоп);
- `cargo test -p plinth-ffi` краснеет на тесте, которого задача не касается: значит, ожидается иной порядок вызовов
  или иное число `relinked`;
- замер хуже чисел планировщика более чем в 6 раз.

## На что смотреть при чтении плана (Opus)

Выбор решения — ваш. Вопросы, на которых план может быть неверен:

1. **Правило «меньший ID» в обе стороны.** Ответ на №6 — не точечная поправка, а симметрия: слияние теперь
   возможно, когда ID потерянного *больше*. Довод — сходимость двух установок на одном треке (мутации M6, M7 это
   показывают). Альтернатива «ждать второго обмена» отвергнута (данные старого телефона не появятся никогда).
2. **Новая операция журнала `Op::Retire`.** Первая операция, стирающая паспорт. Формат документа не меняется, прежнее
   приложение читает такой журнал. Принятое поведение: параллельное описание другой установки паспорт воскрешает.
3. **Идентификаторы заведённых записей выводятся (`derived`).** Без этого симметричное слияние на двух установках
   задваивает прослушивания и записи плейлистов при следующем обмене копиями. Если вам не нравится XOR — нужна
   другая детерминированная функция (UUIDv5 тянет зависимость). Отдельно проверьте, что запись плейлиста и
   прослушивание с выведенным ID действительно не размножаются при слиянии *в журнале*: прослушивания — в проекции
   (`ON CONFLICT(id) DO NOTHING`), записи — регистр по ключу (`id@установка`).
4. **Оценка при споре — по ID, не по времени.** Так и было; теперь это одинаково на обеих установках. Если важнее
   «поставлено позже» — в `JournalState` нужно время оценки (не в этой задаче).
5. **Решения о склейке и блокировки не переносятся**, а паспорт слитого трека снимается (принятое ограничение 1).
   Пока никто, кроме `start_over`, их не пишет. Если хотите перестраховаться, перенос — ещё ~15 строк в `fold_ops`
   и два теста; я не делал, потому что без пишущего их кода это непроверяемо ничем, кроме выдуманных данных.
6. **Пачки по 500 операций** (`FOLD_CHUNK`): пара остаётся целой, но слияние тысяч пар — несколько правок журнала, а не
   одна. Причина — свойство `Journal::record_all` (см. «Замечено, не в этой задаче»).
7. **Удаление `best_match`** — уборка; если считаете, что публичная функция может понадобиться, оставьте.
8. **Шаги `free` → `held` без общей раздачи.** Занятый трек не отбирает потерянный паспорт у свободного, даже если
   сходство с занятым выше.

## Замечено планировщиком (для Opus)

- **Временная реализация по плану** (в ветку не входит, исполнителю не обязательна): патч
  `r9d-reference-implementation.patch` в папке заметок сессии
  (`C:\Users\User\AppData\Roaming\Claude\side-session-notes\local_6a10ff5e-f13f-4d35-84fc-3a8758d8fb83\`); скрипт замера —
  `r9d-scale-measure.rs.txt` там же (он же приведён выше). С ней зелёные: все защищённые тесты (13), весь
  `cargo test -p plinth-sync -p plinth-library -p plinth-ffi`, `cargo clippy -p plinth-sync -p plinth-library --all-targets
  -- -D warnings`. Без неё (на `main`): 10 из 13 защищённых тестов красные утверждением, три охранных зелёные.
- **Мутации** (временная реализация, правка одной-двух строк в `relink.rs`; запускались все `r9d_*`, `passports`, `mirror`,
  `r1_2_moved_files`; без мутации и после отката — всё зелёное):

  | Мутация | Кто ловит |
  |---|---|
  | M1. слияния нет, когда ID потерянного больше (прежнее правило) | `a_track_folded_onto_a_local_one_…`, все четыре теста `r9d_two_installations` |
  | M2. паспорт слитого трека не снимается | `a_merged_track_does_not_come_back_…`, `a_track_folded_onto_a_local_one_…`, `a_second_exchange_changes_nothing`; из прежних — `passports::a_track_with_data_of_this_installation_…` (повторный `relink` не 0) |
  | M3a. случайный ID записи плейлиста вместо `derived` | `independent_moves_of_the_same_data_do_not_double_it` |
  | M3b. случайный ID прослушивания вместо `derived` | `independent_moves_…`, `a_second_exchange_changes_nothing` |
  | M4a. ранг шага 1 не смотрит на доступность | `the_data_goes_to_the_copy_that_plays` |
  | M4b. ранг шага 2 не смотрит на доступность | `the_busy_copy_that_plays_is_preferred_too` |
  | M5a. шаг 1 жадный (`best_match` по очереди) | `the_album_file_keeps_the_album_data`, `the_data_goes_to_the_copy_that_plays` |
  | M5b. шаг 2 жадный | `busy_copies_are_paired_jointly_too`, `the_busy_copy_that_plays_is_preferred_too` |
  | M6. каталог всегда берёт ID потерянного (без «меньшего ID») | `a_track_folded_onto_a_local_one_…`, все четыре теста `r9d_two_installations` |
  | M7. каталог никогда не меняет ID (данные всегда на трек каталога) | восемь тестов, среди них прежний `passports::a_track_with_data_of_this_installation_…` |

  Не ловится тестом: размер пачки `FOLD_CHUNK` (скорость, не результат) — держится замером; пропуск записей
  несуществующего плейлиста (его нет в плане).

## Замечено, не в этой задаче

- **`Journal::record_all` растёт квадратом от размера пачки:** 3 000 прослушиваний одной правкой — 0,25 с, 6 000 —
  1,05 с, 12 000 — 5,25 с (debug), пачками по 500 — 0,06 / 0,12 / 0,23 с. Причину (скорее всего вставка в конец
  массива `plays` в `yrs`) не выясняли. Задето всё, что пишет много прослушиваний разом: **`start_over`**
  (`database_ops` → один `write_all` на всю историю; 50 тыс. прослушиваний — минуты в debug) и импорт истории. Это для
  Р9д (`start_over`); в этой задаче обойдено пачками.
- **Запись плейлиста, которого в журнале нет** (его удалили на одной установке, пока на другой добавляли): `fold_ops`
  кладёт её через `AddEntry`, а он отказывает с `Unavailable` и откатывает всю пачку — `relink` падает целиком, пока
  запись не исчезнет. Было и раньше; шире стало лишь потому, что слияний теперь больше. Лечится в `fold_ops`
  пропуском записей плейлистов, которых нет в `state.playlists` (в `ByTrack` их нет под рукой — понадобится
  множество плейлистов).
- **Равный счёт у разных длительностей:** `likeness` даёт +1 за любую разницу длительности до 2 с, поэтому 398,0 и
  398,5 с — одно и то же. Ближайшая длительность как довод при равенстве разделила бы почти одинаковые синглы; это
  правило общее со сканом (`pair_up`), не здесь.
- **`describe_changed` после «Восстановить» не зовётся** (только после скана): паспорт перешедшего на ID журнала трека
  держит старое описание до ближайшего скана. Безвредно.
- **№20** (паспорта треков вне каталога теряет `start_over`) и **№5** — Р9д.

## Отчёт исполнителя

_Заполняет исполнитель: что сделано, что не вышло и почему, коммиты, вывод проверок (коротко), замер, мутации._
