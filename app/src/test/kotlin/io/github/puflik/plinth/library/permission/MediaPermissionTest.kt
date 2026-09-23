package io.github.puflik.plinth.library.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaPermissionTest {
    @Test
    fun `android 13 and later asks for audio only`() {
        assertThat(MediaPermission.name(sdk = 33)).isEqualTo("android.permission.READ_MEDIA_AUDIO")
        assertThat(MediaPermission.name(sdk = 36)).isEqualTo("android.permission.READ_MEDIA_AUDIO")
    }

    @Test
    fun `older android asks for the whole shared storage`() {
        assertThat(MediaPermission.name(sdk = 32)).isEqualTo("android.permission.READ_EXTERNAL_STORAGE")
        assertThat(MediaPermission.name(sdk = 26)).isEqualTo("android.permission.READ_EXTERNAL_STORAGE")
    }
}
