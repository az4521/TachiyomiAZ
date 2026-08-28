package eu.kanade.tachiyomi.source.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SMangaImplAbiTest {
    @Test
    fun `exposes camel case thumbnail URL accessors used by extensions`() {
        val setter = SMangaImpl::class.java.getMethod("setThumbnailUrl", String::class.java)
        val getter = SMangaImpl::class.java.getMethod("getThumbnailUrl")
        val manga = SMangaImpl()

        assertNotNull(setter)
        setter.invoke(manga, "https://example.invalid/cover.jpg")
        assertEquals("https://example.invalid/cover.jpg", getter.invoke(manga))
        assertEquals("https://example.invalid/cover.jpg", manga.thumbnail_url)
    }

    @Test
    fun `exposes camel case update strategy accessors used by extensions`() {
        val setter = SMangaImpl::class.java.getMethod("setUpdateStrategy", UpdateStrategy::class.java)
        val getter = SMangaImpl::class.java.getMethod("getUpdateStrategy")
        val manga = SMangaImpl()

        assertNotNull(setter)
        setter.invoke(manga, UpdateStrategy.ONLY_FETCH_ONCE)
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, getter.invoke(manga))
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, manga.update_strategy)
    }
}
