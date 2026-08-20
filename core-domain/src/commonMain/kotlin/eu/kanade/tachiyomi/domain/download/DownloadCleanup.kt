package eu.kanade.tachiyomi.domain.download

import eu.kanade.tachiyomi.data.database.models.Chapter

/**
 * Which downloaded chapters to delete as the reader moves through a title.
 *
 * Downloads are the one thing in the app that grows without bound: every chapter read leaves its
 * pages on disk. Both apps can delete a chapter's download once it is read; only the Android one
 * could also keep the last few read chapters and delete behind them, which is the part this adds.
 *
 * The decision is the rule; the deleting is not. Removing files is platform work and stays with
 * each app's download manager, so this returns *what* to delete and nothing else.
 */
object DownloadCleanup {
    /** [slots] value that disables the positional rule. */
    const val KEEP_ALL = -1

    /**
     * The position whose download should go, now that the chapter at [readIndex] has been read.
     *
     * Positions are in **reading order** -- oldest first, the order the reader pages through them.
     * That is worth stating because neither app holds its chapter list that way: both sort newest
     * first for display, and running this rule over such a list picks chapters *ahead* of the
     * reader, deleting the pages they were about to read. Callers reverse first.
     *
     * [slots] counts backwards from the chapter just read, matching the settings entries: 0 is
     * that chapter itself, 1 the one before it, and so on, while [KEEP_ALL] turns the rule off.
     * Null when the rule is off or nothing is far enough back, which is the ordinary case early in
     * a title rather than an error.
     *
     * This is expressed as an index rather than a chapter so that a caller holding its own chapter
     * type can use it without converting a whole list to get at one element.
     */
    fun indexToDeleteAfterRead(
        count: Int,
        readIndex: Int,
        slots: Int
    ): Int? {
        if (slots == KEEP_ALL || slots < 0) return null
        if (readIndex < 0 || readIndex >= count) return null
        val target = readIndex - slots
        return if (target in 0 until count) target else null
    }

    /** [indexToDeleteAfterRead] for a caller holding the shared chapter model. */
    fun chapterToDeleteAfterRead(
        chapters: List<Chapter>,
        readIndex: Int,
        slots: Int
    ): Chapter? = indexToDeleteAfterRead(chapters.size, readIndex, slots)?.let(chapters::get)

    /**
     * The same decision, for callers that hold a chapter rather than its position.
     *
     * Identity is by url within the title, since that is what both apps key a chapter's downloaded
     * pages by; two chapters can share a number, and an unnumbered one carries the -1 sentinel.
     */
    fun chapterToDeleteAfterRead(
        chapters: List<Chapter>,
        read: Chapter,
        slots: Int
    ): Chapter? {
        val index = chapters.indexOfFirst { it.url == read.url }
        if (index == -1) return null
        return chapterToDeleteAfterRead(chapters, index, slots)
    }
}
