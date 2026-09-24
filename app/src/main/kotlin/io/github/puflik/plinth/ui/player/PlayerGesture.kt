package io.github.puflik.plinth.ui.player

import io.github.puflik.plinth.ui.player.GestureAction.CHANGE_TRACK
import io.github.puflik.plinth.ui.player.GestureAction.COLLAPSE
import io.github.puflik.plinth.ui.player.GestureAction.FAST_SEEK
import io.github.puflik.plinth.ui.player.GestureAction.LIKE
import io.github.puflik.plinth.ui.player.GestureAction.NEXT_ALBUM
import io.github.puflik.plinth.ui.player.GestureAction.NEXT_PANEL
import io.github.puflik.plinth.ui.player.GestureAction.NOTHING
import io.github.puflik.plinth.ui.player.GestureAction.OPEN_LYRICS
import io.github.puflik.plinth.ui.player.GestureAction.OPEN_QUEUE
import io.github.puflik.plinth.ui.player.GestureAction.OPEN_SIMILAR
import io.github.puflik.plinth.ui.player.GestureAction.PLAY_PAUSE
import io.github.puflik.plinth.ui.player.GestureAction.SEEK_10_SECONDS
import io.github.puflik.plinth.ui.player.GestureAction.SWITCH_PANEL
import io.github.puflik.plinth.ui.player.GestureAction.TOGGLE_LYRICS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Что жест может сделать (12.11). Направление жеста — «вперёд» или «назад»
 * ([command]) — у смены трека, панелей и перемотки своё.
 */
enum class GestureAction {
    CHANGE_TRACK,
    SWITCH_PANEL,
    OPEN_QUEUE,
    OPEN_LYRICS,
    OPEN_SIMILAR,
    COLLAPSE,
    TOGGLE_LYRICS,
    PLAY_PAUSE,
    NEXT_PANEL,
    SEEK_10_SECONDS,

    /** Лайк отложен до v0.2 (решение автора): пока ничего не делает. */
    LIKE,
    FAST_SEEK,

    /** Нужна очередь по альбомам — пока ничего не делает. */
    NEXT_ALBUM,
    NOTHING,
    ;

    /**
     * Команда плееру, когда жест сделан в сторону [forward] при открытой
     * панели [panel]; `null` — делать нечего.
     */
    fun command(
        forward: Boolean,
        panel: PlayerPanel,
    ): PlayerCommand? =
        when (this) {
            in PANEL_ACTIONS -> PlayerCommand.Show(panelAfter(forward, panel))
            CHANGE_TRACK -> if (forward) PlayerCommand.Next else PlayerCommand.Previous
            COLLAPSE -> PlayerCommand.Collapse
            PLAY_PAUSE -> PlayerCommand.PlayPause
            SEEK_10_SECONDS -> PlayerCommand.SeekBy(if (forward) SEEK_STEP else -SEEK_STEP)
            FAST_SEEK -> PlayerCommand.SeekBy(if (forward) FAST_SEEK_STEP else -FAST_SEEK_STEP)
            else -> null
        }

    /** Какую панель показать после жеста-панели; для прочих действий не зовётся. */
    private fun panelAfter(
        forward: Boolean,
        panel: PlayerPanel,
    ): PlayerPanel =
        when (this) {
            SWITCH_PANEL -> panel.step(if (forward) 1 else -1)
            NEXT_PANEL -> panel.step(1)
            OPEN_QUEUE -> PlayerPanel.QUEUE
            OPEN_LYRICS -> PlayerPanel.LYRICS
            OPEN_SIMILAR -> PlayerPanel.SIMILAR
            else -> if (panel == PlayerPanel.LYRICS) PlayerPanel.COVER else PlayerPanel.LYRICS
        }

    private fun PlayerPanel.step(by: Int): PlayerPanel {
        val panels = PlayerPanel.entries
        return panels[(ordinal + by).mod(panels.size)]
    }

    private companion object {
        val SEEK_STEP = 10.seconds
        val FAST_SEEK_STEP = PlayerCommand.FAST_SEEK_STEP

        /** Действия, которые открывают панель. */
        val PANEL_ACTIONS = setOf(SWITCH_PANEL, NEXT_PANEL, OPEN_QUEUE, OPEN_LYRICS, OPEN_SIMILAR, TOGGLE_LYRICS)
    }
}

/**
 * Жесты плеера таблицей (E5, 12.11): умолчание и варианты, которые позже
 * предложит экран настройки. Пока настройки нет, играет [default].
 */
enum class PlayerGesture(
    val default: GestureAction,
    val options: List<GestureAction>,
) {
    /** Свайп по обложке влево и вправо; панели — точками-табами (умолчание B). */
    COVER_SWIPE(CHANGE_TRACK, listOf(CHANGE_TRACK, SWITCH_PANEL, NOTHING)),
    SWIPE_UP(OPEN_QUEUE, listOf(OPEN_QUEUE, OPEN_LYRICS, OPEN_SIMILAR, NOTHING)),
    SWIPE_DOWN(COLLAPSE, listOf(COLLAPSE, NOTHING)),
    COVER_TAP(TOGGLE_LYRICS, listOf(TOGGLE_LYRICS, PLAY_PAUSE, NEXT_PANEL, NOTHING)),

    /** Двойной тап по левой или правой половине обложки. */
    DOUBLE_TAP(SEEK_10_SECONDS, listOf(SEEK_10_SECONDS, LIKE, NOTHING)),

    /** Удержание ⏮ или ⏭. */
    HOLD_SKIP(FAST_SEEK, listOf(FAST_SEEK, NEXT_ALBUM, NOTHING)),
    MINI_PLAYER_SWIPE(CHANGE_TRACK, listOf(CHANGE_TRACK, NOTHING)),
}

/** Что жест велит плееру. */
sealed interface PlayerCommand {
    data object Next : PlayerCommand

    data object Previous : PlayerCommand

    data class Show(
        val panel: PlayerPanel,
    ) : PlayerCommand

    data object Collapse : PlayerCommand

    data object PlayPause : PlayerCommand

    data class SeekBy(
        val delta: Duration,
    ) : PlayerCommand

    companion object {
        /** Шаг быстрой перемотки за один такт удержания. */
        val FAST_SEEK_STEP: Duration = 5.seconds
    }
}
