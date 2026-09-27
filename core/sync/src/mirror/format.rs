//! Файл копии журнала: заголовок, время копии, контрольная сумма и полный
//! снимок журнала (update v2 `yrs`). Файл лежит в папке человека и может
//! оказаться обрезанным, испорченным или чужим — такой не читается.

use plinth_types::{CoreError, Timestamp};

const MAGIC: [u8; 4] = *b"PLNM";
/// Версия формата копии. Копия новее — не читается: её сделала более новая
/// версия приложения.
const FORMAT: u8 = 1;
/// Магия, формат, время копии, CRC снимка.
const HEADER_LEN: usize = 4 + 1 + 8 + 4;

/// Прочитанная копия.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Copy<'a> {
    /// Когда копия записана — по часам устройства, которое её писало.
    pub written_at: Timestamp,
    /// Снимок журнала, update v2.
    pub snapshot: &'a [u8],
}

/// Файл копии со снимком `snapshot`, записанный в `written_at`.
pub fn encode(snapshot: &[u8], written_at: Timestamp) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(HEADER_LEN + snapshot.len());
    bytes.extend_from_slice(&MAGIC);
    bytes.push(FORMAT);
    bytes.extend_from_slice(&written_at.as_millis().to_le_bytes());
    bytes.extend_from_slice(&crc32fast::hash(snapshot).to_le_bytes());
    bytes.extend_from_slice(snapshot);
    bytes
}

/// Копия из байтов файла; не копия, испорчена или новее приложения — ошибка.
pub fn decode(bytes: &[u8]) -> Result<Copy<'_>, CoreError> {
    let Some((header, snapshot)) = bytes.split_at_checked(HEADER_LEN) else {
        return Err(unreadable("too short"));
    };
    if header[..4] != MAGIC {
        return Err(unreadable("not a journal copy"));
    }
    if header[4] > FORMAT {
        return Err(unreadable(&format!("format {} is newer than {FORMAT}", header[4])));
    }
    let millis = i64::from_le_bytes(header[5..13].try_into().map_err(|_| unreadable("time"))?);
    let crc = u32::from_le_bytes(header[13..17].try_into().map_err(|_| unreadable("checksum"))?);
    if crc32fast::hash(snapshot) != crc {
        return Err(unreadable("checksum mismatch"));
    }
    Ok(Copy { written_at: Timestamp::from_millis(millis), snapshot })
}

fn unreadable(what: &str) -> CoreError {
    CoreError::parse(format!("journal copy: {what}"))
}

#[cfg(test)]
mod tests {
    use plinth_types::{CoreError, Timestamp};

    use super::{Copy, FORMAT, decode, encode};

    const AT: Timestamp = Timestamp::from_millis(1_790_307_000_000);

    #[test]
    fn a_copy_reads_back_with_its_time() {
        let bytes = encode(b"snapshot bytes", AT);

        assert_eq!(decode(&bytes).unwrap(), Copy { written_at: AT, snapshot: b"snapshot bytes" });
    }

    #[test]
    fn a_damaged_cut_or_foreign_file_is_not_a_copy() {
        let bytes = encode(b"snapshot bytes", AT);
        let mut flipped = bytes.clone();
        let last = flipped.len() - 1;
        flipped[last] ^= 0x01;
        let mut foreign = bytes.clone();
        foreign[0] = b'X';

        for broken in [&flipped[..], &bytes[..bytes.len() - 1], &bytes[..10], &foreign[..], b"#EXTM3U\n"] {
            assert!(matches!(decode(broken), Err(CoreError::Parse { .. })), "{broken:?}");
        }
    }

    #[test]
    fn a_copy_from_a_newer_app_is_not_read() {
        let mut bytes = encode(b"snapshot bytes", AT);
        bytes[4] = FORMAT + 1;

        assert!(decode(&bytes).is_err());
    }
}
