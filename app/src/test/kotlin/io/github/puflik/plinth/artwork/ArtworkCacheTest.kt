package io.github.puflik.plinth.artwork

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Кэш обложек в памяти: знает и найденные картинки, и то, что картинки нет. */
class ArtworkCacheTest {
    private val cache = ArtworkCache<String>(maxBytes = 10) { it.length }

    @Test
    fun `remembers found and missing artwork`() {
        cache.put(key("song"), "cover")
        cache.put(key("bare"), null)

        assertThat(cache[key("song")]).isEqualTo(Artwork.Found("cover"))
        assertThat(cache[key("bare")]).isEqualTo(Artwork.Missing)
        assertThat(cache[key("unknown")]).isNull()
    }

    @Test
    fun `least recently used artwork leaves first when over budget`() {
        cache.put(key("a"), "aaaa")
        cache.put(key("b"), "bbbb")
        cache[key("a")]

        cache.put(key("c"), "cccc")

        assertThat(cache[key("b")]).isNull()
        assertThat(cache[key("a")]).isEqualTo(Artwork.Found("aaaa"))
        assertThat(cache[key("c")]).isEqualTo(Artwork.Found("cccc"))
    }

    @Test
    fun `artwork bigger than the whole budget is not kept`() {
        cache.put(key("a"), "aaaa")

        cache.put(key("huge"), "x".repeat(11))

        assertThat(cache[key("huge")]).isNull()
        assertThat(cache[key("a")]).isEqualTo(Artwork.Found("aaaa"))
    }

    @Test
    fun `replaced artwork frees its bytes`() {
        cache.put(key("a"), "aaaaaaaa")
        cache.put(key("a"), "a")

        cache.put(key("b"), "bbbbbbbb")

        assertThat(cache[key("a")]).isEqualTo(Artwork.Found("a"))
        assertThat(cache[key("b")]).isEqualTo(Artwork.Found("bbbbbbbb"))
    }

    private fun key(uri: String) = ArtworkKey(uri, ArtworkSize.THUMBNAIL)
}
