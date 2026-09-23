package io.github.puflik.plinth.library.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.puflik.plinth.library.db.dao.TrackDao
import io.github.puflik.plinth.library.db.entity.TrackEntity

/**
 * База фонотеки v0.1 (C3.1): одна таблица треков.
 *
 * Временная, как весь эпик C: в v0.2 её заменит хранилище ядра на Rust.
 * Схема каждой версии лежит в `app/schemas/` — по ней пишутся миграции.
 */
@Database(entities = [TrackEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class PlinthDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        /** Файл базы в каталоге приложения. */
        const val NAME = "plinth.db"
    }
}
