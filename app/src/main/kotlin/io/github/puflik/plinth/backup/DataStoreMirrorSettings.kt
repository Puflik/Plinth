package io.github.puflik.plinth.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [MirrorSettings] в DataStore — адрес дерева строкой. В Auto Backup
 * настройки не попадают: после переустановки разрешения на папку нет, её
 * выбирают заново.
 */
class DataStoreMirrorSettings(
    private val store: DataStore<Preferences>,
) : MirrorSettings {
    override val folder: Flow<String?> = store.data.map { it[FOLDER] }.distinctUntilChanged()

    override suspend fun setFolder(tree: String) {
        store.edit { it[FOLDER] = tree }
    }

    private companion object {
        val FOLDER = stringPreferencesKey("mirror_folder")
    }
}
