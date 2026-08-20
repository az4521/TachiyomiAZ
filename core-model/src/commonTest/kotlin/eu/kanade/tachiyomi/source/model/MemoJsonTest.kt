package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoJsonTest {
    @Test
    fun `wire memo is restored into source models`() {
        val manga = SManga.create()
        val chapter = SChapter.create()

        setMangaMemoJson(manga, "{\"token\":\"manga\"}")
        setChapterMemoJson(chapter, "{\"token\":\"chapter\"}")

        assertEquals(JsonPrimitive("manga"), manga.memo["token"])
        assertEquals(JsonPrimitive("chapter"), chapter.memo["token"])
    }

    @Test
    fun `blank wire memo clears source models`() {
        val manga = SManga.create()
        setMangaMemoJson(manga, "{\"token\":\"old\"}")

        setMangaMemoJson(manga, null)

        assertEquals(emptyMap(), manga.memo)
    }

    @Test
    fun `copyFrom applies an explicitly empty memo`() {
        val manga = SManga.create()
        setMangaMemoJson(manga, "{\"token\":\"old\"}")

        manga.copyFrom(SManga.create())

        assertEquals(emptyMap(), manga.memo)
    }
}
