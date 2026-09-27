use plinth_types::QualityTier;

use super::ResultKind;

/// Что умеет провайдер (E1.2). Общий контракт проверяет, что это правда:
/// поиск не отдаёт видов, которых нет в `finds`, варианты источника не
/// лучше `best_quality`. `related` и загрузка появятся с Bandcamp (v0.5).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Capabilities {
    /// Какие результаты даёт поиск.
    pub finds: Vec<ResultKind>,
    /// Лучшее качество, которое бывает у провайдера; по нему реестр ставит
    /// провайдеров в порядок (plan.md 6.4).
    pub best_quality: QualityTier,
}

impl Capabilities {
    pub fn finds(&self, kind: ResultKind) -> bool {
        self.finds.contains(&kind)
    }
}

#[cfg(test)]
mod tests {
    use plinth_types::QualityTier;

    use super::Capabilities;
    use crate::model::ResultKind;

    #[test]
    fn knows_which_results_it_finds() {
        let archive = Capabilities { finds: vec![ResultKind::Album], best_quality: QualityTier::Lossless };

        assert!(archive.finds(ResultKind::Album));
        assert!(!archive.finds(ResultKind::Track));
    }
}
