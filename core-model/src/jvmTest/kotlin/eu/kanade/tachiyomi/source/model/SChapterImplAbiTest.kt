package eu.kanade.tachiyomi.source.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SChapterImplAbiTest {
    @Test
    fun `exposes camel case chapter number accessors used by extensions`() {
        val setter = SChapterImpl::class.java.getMethod("setChapterNumber", Float::class.javaPrimitiveType)
        val getter = SChapterImpl::class.java.getMethod("getChapterNumber")
        val chapter = SChapterImpl()

        assertNotNull(setter)
        setter.invoke(chapter, 12.5f)
        assertEquals(12.5f, getter.invoke(chapter))
        assertEquals(12.5f, chapter.chapter_number)
    }

    @Test
    fun `exposes camel case upload date accessors used by extensions`() {
        val setter = SChapterImpl::class.java.getMethod("setDateUpload", Long::class.javaPrimitiveType)
        val getter = SChapterImpl::class.java.getMethod("getDateUpload")
        val chapter = SChapterImpl()

        assertNotNull(setter)
        setter.invoke(chapter, 1234L)
        assertEquals(1234L, getter.invoke(chapter))
        assertEquals(1234L, chapter.date_upload)
    }
}
