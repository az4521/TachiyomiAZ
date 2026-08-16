package eu.kanade.tachiyomi.data.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Default rather than IO: `Dispatchers.IO` is internal on Kotlin/Native in this coroutines
 * version, so it cannot be named here. SQLDelight's NativeSqliteDriver does its own connection
 * handling, and query mapping is CPU work rather than blocking IO, so Default is the right pool
 * anyway.
 */
actual val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default
