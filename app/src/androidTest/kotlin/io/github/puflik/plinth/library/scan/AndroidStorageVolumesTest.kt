package io.github.puflik.plinth.library.scan

import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Тома для скана ядра (D3c): корни общего хранилища — основного и SD-карт,
 * а не папки приложения на них.
 */
class AndroidStorageVolumesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION") // Корень основного тома — ради сравнения.
    @Test
    fun the_primary_volume_is_the_root_of_shared_storage() {
        val roots = AndroidStorageVolumes(context).roots()

        assertThat(roots).contains(Environment.getExternalStorageDirectory())
        assertThat(roots.map { it.path }).containsNoDuplicates()
        roots.forEach { assertThat(it.path).doesNotContain("/Android/") }
    }
}
