package eu.kanade.tachiyomi.data.database.models

class LibraryManga : MangaImpl() {
    var unread: Int = 0

    /**
     * Chapters marked read.
     *
     * Carried alongside [unread] so "has this been started" is answerable from a library row. The
     * library-update skip settings need it, and without it each app would have to fetch every
     * chapter of every entry to find out -- once per update, on both platforms.
     */
    var read: Int = 0

    var category: Int = 0

    /** Whether the user has read anything of this title. */
    val hasStarted: Boolean
        get() = read > 0
}
