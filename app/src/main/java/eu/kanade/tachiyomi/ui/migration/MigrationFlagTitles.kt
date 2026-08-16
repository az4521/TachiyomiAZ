package eu.kanade.tachiyomi.ui.migration

import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.domain.migration.MigrationFlags

/**
 * Labels for [MigrationFlags], in the same order as its `flags`.
 *
 * Kept here rather than with the flags themselves: the meaning of each bit is shared, but string
 * resources are Android-only.
 */
val MigrationFlags.titles get() = arrayOf(R.string.chapters, R.string.categories, R.string.track)
