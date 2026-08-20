package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaExtensionsTest {
    @Test
    fun `copyMemoFrom allows a source to clear stored memo`() {
        val manga = Manga.create(source = 1).apply {
            memo = buildJsonObject { put("source", JsonPrimitive("state")) }
        }
        val update = SManga.create()

        assertTrue(manga.copyMemoFrom(update))
        assertEquals(emptyMap(), manga.memo)
    }
}
