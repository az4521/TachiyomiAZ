package eu.kanade.tachiyomi.data.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * Opens the database on iOS, with SQLDelight owning the schema outright.
 *
 * This is the half of the asymmetry that Android does not get. There are no pre-existing iOS
 * databases to preserve, so [Database.Schema] creates and migrates the tables directly from the
 * .sq files. On Android the same .sq files only generate typed queries, and DbOpenCallback keeps
 * ownership of creation and its 18 upgrade steps so real user databases are never touched by two
 * things at once.
 *
 * Because both platforms read the same .sq definitions, the tables cannot drift apart.
 */
object IosDatabaseFactory {
    const val DATABASE_NAME: String = "tachiyomi.db"

    fun createDriver(name: String = DATABASE_NAME): SqlDriver = NativeSqliteDriver(Database.Schema, name)

    fun create(driver: SqlDriver = createDriver()): Database = Database(driver)
}
