package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Chapter number recognition, which is the subtlest logic in the shared layer: it is a pile of
 * regexes run against free-text chapter names from hundreds of sources.
 *
 * It matters for more than display. Sync uses the recognised number to carry read state across a
 * source deleting and re-adding a chapter, so getting it wrong marks the wrong chapters read --
 * and the two platforms disagreeing would mean the same library reads differently on each.
 */
class ChapterRecognitionTest {
    private fun manga(title: String = "Test Manga") =
        SManga.create().apply {
            url = "/manga"
            this.title = title
        }

    private fun numberOf(
        name: String,
        mangaTitle: String = "Test Manga"
    ): Float {
        val chapter =
            SChapter.create().apply {
                url = "/c"
                this.name = name
            }
        ChapterRecognition.parseChapterNumber(chapter, manga(mangaTitle))
        return chapter.chapter_number
    }

    @Test
    fun `Ch prefix is recognised`() {
        assertEquals(4f, numberOf("Mokushiroku Alice Vol.1 Ch. 4: Misrepresentation"))
    }

    @Test
    fun `a bare number is recognised`() {
        assertEquals(10f, numberOf("Chapter 10"))
    }

    @Test
    fun `decimal chapters are preserved`() {
        // Half chapters are common and must not round, or they collide with the whole chapter.
        assertEquals(10.5f, numberOf("Chapter 10.5"))
    }

    @Test
    fun `the manga title is not mistaken for a chapter number`() {
        // The title is stripped first; otherwise "Ai Yori Aoshi 100" reads as chapter 100 of
        // a series whose name happens to contain digits.
        assertEquals(5f, numberOf("Ai Yori Aoshi 5", mangaTitle = "Ai Yori Aoshi"))
    }

    @Test
    fun `a volume number is not mistaken for a chapter number`() {
        assertEquals(3f, numberOf("Vol.2 Ch.3"))
    }

    @Test
    fun `an unparseable name yields the not-recognised sentinel`() {
        // -1 means "no number found"; sync checks isRecognizedNumber before using it, so this
        // sentinel is what stops unnumbered chapters being treated as chapter 0.
        assertEquals(-1f, numberOf("Extras"))
    }

    @Test
    fun `an already known number is left alone`() {
        val chapter =
            SChapter.create().apply {
                url = "/c"
                name = "Chapter 99"
                chapter_number = 7f
            }
        ChapterRecognition.parseChapterNumber(chapter, manga())
        assertEquals(7f, chapter.chapter_number, "a number the source supplied must win over the name")
    }
}
