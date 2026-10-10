//! Журнал заново (Р1.4, ADR 0007): снимок, который не читается, больше не
//! запирает ядро. Он откладывается рядом копией, а журнал начинается с тем
//! пользовательским, что показывает база, — ровно с тем, что пишет в неё
//! `rebuild::write`. Хвост прежнего журнала новый не читает: его кадры —
//! правки старого документа, а их данные уже в базе.

use std::path::Path;

use plinth_library::db::Database;
use plinth_library::model::{Setting, SyncedSettings};
use plinth_types::{CoreError, DeviceId};
use yrs::{Doc, Transact};

use super::doc::Roots;
use super::op::Op;
use super::op_meta::OpMeta;
use super::store::{self, Snapshot};
use super::{apply_snapshot, snapshot};

/// Почему снимок журнала в каталоге `dir` не читается: сумма не сошлась,
/// файл обрезан, не снимок журнала или `yrs` его не разбирает (в том числе
/// паникой). `None` — снимка нет, он цел или записан более новой версией
/// приложения: такой не откладывают. Ошибка — файл не прочесть. Ничего не
/// пишет и не создаёт.
pub fn damaged_snapshot(dir: &Path) -> Result<Option<String>, CoreError> {
    Ok(match store::inspect_snapshot(dir)? {
        None | Some(Snapshot::Newer) => None,
        Some(Snapshot::Damaged(reason)) => Some(reason),
        // У нового журнала данных нет: `Journal::open` их не разбирает.
        Some(Snapshot::Readable(payload)) if payload.is_empty() => None,
        Some(Snapshot::Readable(payload)) => {
            let doc = Doc::new();
            let _roots = Roots::new(&doc);
            apply_snapshot(&doc, &payload).err().map(|reason| format!("unreadable snapshot: {reason}"))
        }
    })
}

/// Начинает журнал в `dir` заново, когда его снимок не читается
/// ([`damaged_snapshot`]), — с пользовательским, которое показывает база
/// `db`; автор записей — `device`. Испорченный снимок остаётся рядом копией
/// `snapshot.damaged-<мс>`; новый, с новым идентификатором журнала, встаёт на
/// его место одной заменой файла: обрыв до неё оставляет испорченный снимок,
/// и следующая попытка повторит всё. Хвост прежнего журнала новый не
/// читает — `Journal::open` отложит его как чужой.
///
/// Сколько записей перенесено; ноль — в базе ничего не было, журнал пуст, как
/// у нового. Снимок читается, его нет или он новее приложения — ошибка,
/// файлы не тронуты.
pub fn start_over(dir: &Path, device: DeviceId, db: &Database) -> Result<usize, CoreError> {
    let Some(reason) = damaged_snapshot(dir)? else {
        return Err(CoreError::storage("journal: the snapshot is readable, there is nothing to start over"));
    };
    // Сначала всё перенесённое собрано из базы: её ошибка — отказ, на диске
    // ничего не изменилось.
    let ops = database_ops(db)?;
    let doc = Doc::new();
    let roots = Roots::new(&doc);
    let carried = {
        let meta = OpMeta::now(device);
        let mut txn = doc.transact_mut();
        roots.write_all(&mut txn, &ops, &meta)?
    };
    let payload = if carried > 0 { snapshot(&doc) } else { Vec::new() };

    store::keep_snapshot_aside(dir)?;
    store::replace_snapshot(dir, u64::from(carried > 0), &payload)?;
    log::warn!("journal: the snapshot is unreadable ({reason}); started over with {carried} records from the database");
    Ok(carried)
}

/// Что показывает проекция, операциями: то, что пишет в неё `rebuild::write`.
/// Паспорта треков не переносятся — их дописывает `describe_missing` при
/// открытии ядра.
fn database_ops(db: &Database) -> Result<Vec<Op>, CoreError> {
    let mut ops = Vec::new();
    for data in db.all_user_data()? {
        if data.liked {
            ops.push(Op::Like { track: data.track });
        }
        if data.rating.is_some() {
            ops.push(Op::Rate { track: data.track, rating: data.rating });
        }
    }
    ops.extend(db.recent_plays(u32::MAX)?.into_iter().map(Op::Play));
    for playlist in db.playlists()? {
        let entries = db.entries(playlist.id)?;
        ops.push(Op::CreatePlaylist(playlist));
        ops.extend(entries.into_iter().map(Op::AddEntry));
    }
    ops.extend(db.merge_decisions()?.into_iter().map(Op::Decide));
    ops.extend(db.subscriptions()?.into_iter().map(Op::Subscribe));
    ops.extend(db.blocklist()?.into_iter().map(Op::Block));
    // Только то, что отличается от значения по умолчанию: запись умолчания —
    // выбор, которого человек не делал, а при синхронизации он перебил бы
    // выбор другого устройства.
    let settings = db.synced_settings()?;
    if settings.version_preference != SyncedSettings::default().version_preference {
        ops.push(Op::Set(Setting::VersionPreference(settings.version_preference)));
    }
    Ok(ops)
}
