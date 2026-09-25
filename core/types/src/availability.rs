//! Доступность источника и трека (A1.2).

/// Можно ли сейчас получить байты. Порядок — от худшего к лучшему.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Availability {
    /// Файла нет, провайдер отдал «нет такого», трек снят с каталога.
    Unavailable,
    /// Играет, но хуже обычного: только стрим без кэша, провайдер отвечает
    /// с ошибками, в сети — только худшее качество.
    Degraded,
    Available,
}

impl Availability {
    /// Доступность трека — лучшая из доступностей его источников.
    pub fn best(sources: impl IntoIterator<Item = Self>) -> Self {
        sources.into_iter().max().unwrap_or(Self::Unavailable)
    }

    pub fn is_playable(self) -> bool {
        self != Self::Unavailable
    }
}

#[cfg(test)]
mod tests {
    use super::Availability;

    #[test]
    fn best_of_sources_wins() {
        let sources = [Availability::Unavailable, Availability::Degraded, Availability::Available];

        assert_eq!(Availability::best(sources), Availability::Available);
        assert_eq!(Availability::best([Availability::Unavailable, Availability::Degraded]), Availability::Degraded);
    }

    #[test]
    fn nothing_to_play_from_is_unavailable() {
        assert_eq!(Availability::best([]), Availability::Unavailable);
    }

    #[test]
    fn only_available_and_degraded_play() {
        assert!(Availability::Available.is_playable());
        assert!(Availability::Degraded.is_playable());
        assert!(!Availability::Unavailable.is_playable());
    }
}
