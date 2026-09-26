package io.github.puflik.plinth.ui.library.playlists

/** Имя плейлиста из поля ввода: без пробелов по краям; пустое — не имя, `null`. */
fun playlistName(input: String): String? = input.trim().ifEmpty { null }
