//! Какие папки сканировать (D1). Правила те же, что у `FolderConfig` в
//! Kotlin v0.1: папка — путь от корня тома (`Music/Queen/`), регистр и
//! крайние `/` не важны (общее хранилище Android регистр не различает),
//! совпадает только целиком — `Music` не захватывает `MusicVideos`.
//! Включённые — со всеми подпапками, кроме исключённых.

/// Включённые и исключённые папки; умолчание плана (13.1) — `Music` и `Download`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FolderConfig {
    pub included: Vec<String>,
    pub excluded: Vec<String>,
}

impl Default for FolderConfig {
    fn default() -> Self {
        Self { included: vec!["Music/".to_owned(), "Download/".to_owned()], excluded: Vec::new() }
    }
}

impl FolderConfig {
    /// Сканируется ли папка `folder` — путь от корня тома.
    pub fn includes(&self, folder: &str) -> bool {
        let path = normalized(folder);
        self.included.iter().any(|f| path.starts_with(&normalized(f)))
            && !self.excluded.iter().any(|f| path.starts_with(&normalized(f)))
    }

    /// С каких папок начинать обход: включённые, кроме вложенных в другие
    /// включённые, — как их записал человек (на Linux регистр важен).
    pub(crate) fn starts(&self) -> Vec<String> {
        let mut starts: Vec<(String, String)> = self.included.iter().map(|f| (normalized(f), canonical(f))).collect();
        starts.sort();
        starts.dedup_by(|a, b| a.0 == b.0);
        let tops: Vec<String> = starts.iter().map(|(n, _)| n.clone()).collect();
        starts
            .into_iter()
            .filter(|(n, _)| !tops.iter().any(|top| top != n && n.starts_with(top.as_str())))
            .map(|(_, c)| c)
            .collect()
    }
}

/// Без крайних `/` и пробелов, строчными, с `/` в конце; корень — пустая строка.
fn normalized(folder: &str) -> String {
    let trimmed = folder.trim().trim_matches('/').to_lowercase();
    if trimmed.is_empty() { String::new() } else { format!("{trimmed}/") }
}

/// Как записал человек, но без крайних `/` и пробелов, с `/` в конце.
fn canonical(folder: &str) -> String {
    let trimmed = folder.trim().trim_matches('/');
    if trimmed.is_empty() { String::new() } else { format!("{trimmed}/") }
}

#[cfg(test)]
mod tests {
    use super::FolderConfig;

    fn config(included: &[&str], excluded: &[&str]) -> FolderConfig {
        FolderConfig {
            included: included.iter().map(|s| (*s).to_owned()).collect(),
            excluded: excluded.iter().map(|s| (*s).to_owned()).collect(),
        }
    }

    #[test]
    fn included_folders_come_with_subfolders_but_whole_names_only() {
        let config = FolderConfig::default();

        assert!(config.includes("Music/"));
        assert!(config.includes("music/Queen/Live/"));
        assert!(config.includes("/Download"));
        assert!(!config.includes("MusicVideos/"));
        assert!(!config.includes("DCIM/"));
        assert!(!config.includes(""));
    }

    #[test]
    fn an_excluded_folder_wins_with_its_subfolders() {
        let config = config(&["Music/"], &["Music/Podcasts"]);

        assert!(config.includes("Music/Rock/"));
        assert!(!config.includes("Music/Podcasts/"));
        assert!(!config.includes("music/podcasts/2024/"));
    }

    #[test]
    fn the_root_includes_everything() {
        let config = config(&[""], &["Android/"]);

        assert!(config.includes(""));
        assert!(config.includes("DCIM/Music/"));
        assert!(!config.includes("Android/media/"));
    }

    /// Обход начинается с верхних папок: вложенная включённая не обходится дважды.
    #[test]
    fn walking_starts_from_the_top_folders_only() {
        let config = config(&["Music/Rock", "Music/", "/music/", "Download/"], &[]);

        assert_eq!(config.starts(), vec!["Download/".to_owned(), "Music/".to_owned()]);
        assert_eq!(
            super::FolderConfig { included: vec![String::new()], excluded: vec![] }.starts(),
            vec![String::new()]
        );
    }
}
