package eu.kanade.tachiyomi.domain.migration

/**
 * What carries over when a manga is migrated to another source.
 *
 * Stored as a bitmask in preferences, so the meaning of each bit is a rule both platforms must
 * read the same way -- a shifted bit would silently migrate the wrong data.
 *
 * The user-facing labels stay in the UI layer; only the meaning lives here.
 */
object MigrationFlags {
    const val CHAPTERS = 0b001
    const val CATEGORIES = 0b010
    const val TRACK = 0b100

    val flags get() = arrayOf(CHAPTERS, CATEGORIES, TRACK)

    fun hasChapters(value: Int): Boolean = value and CHAPTERS != 0

    fun hasCategories(value: Int): Boolean = value and CATEGORIES != 0

    fun hasTracks(value: Int): Boolean = value and TRACK != 0

    /** Indices into [flags] that are set, for driving a multi-select UI. */
    fun getEnabledFlagsPositions(value: Int): List<Int> = flags.mapIndexedNotNull { index, flag -> if (value and flag != 0) index else null }

    /** Inverse of [getEnabledFlagsPositions]. */
    fun getFlagsFromPositions(positions: Array<Int>): Int = positions.fold(0) { accumulated, position -> accumulated or (1 shl position) }
}
