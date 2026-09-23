package io.github.puflik.plinth.library.db

import androidx.room.TypeConverter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Типы модели, которых SQLite не знает (C3.1). */
class Converters {
    /** Длительность — в миллисекундах, как `DURATION` в `MediaStore`. */
    @TypeConverter
    fun durationToMillis(duration: Duration): Long = duration.inWholeMilliseconds

    @TypeConverter
    fun millisToDuration(millis: Long): Duration = millis.milliseconds
}
