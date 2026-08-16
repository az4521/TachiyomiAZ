package eu.kanade.tachiyomi.data.database

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher query results are mapped on.
 *
 * `Dispatchers.IO` cannot be named in common code: coroutines declares it in its JVM and Native
 * source sets, not in the common API, so referring to it directly compiles for Android and plain
 * JVM but not for a shared source set. Expressing it as an expect val keeps every platform on its
 * own blocking-IO dispatcher without commonMain having to know which.
 */
expect val databaseDispatcher: CoroutineDispatcher
