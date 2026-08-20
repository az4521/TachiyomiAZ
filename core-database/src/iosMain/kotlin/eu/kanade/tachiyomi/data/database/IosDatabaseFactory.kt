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

    /**
     * @param name bare file name. It must not contain a path separator -- sqliter rejects one with
     *   `IllegalArgumentException`, and because this is not annotated `@Throws` that surfaces on
     *   the Swift side as a process kill rather than a catchable error. Pass the directory as
     *   [directoryPath] instead.
     * @param directoryPath directory to place the database in, or null for sqliter's default.
     *   Callers should pass an explicit one: the default is chosen by the driver, and a database
     *   whose location the app does not control is one the app cannot back up or migrate.
     */
    fun createDriver(
        name: String = DATABASE_NAME,
        directoryPath: String? = null
    ): SqlDriver =
        NativeSqliteDriver(
            schema = Database.Schema,
            name = name,
            onConfiguration = { configuration ->
                if (directoryPath == null) {
                    configuration
                } else {
                    configuration.copy(
                        extendedConfig = configuration.extendedConfig.copy(basePath = directoryPath)
                    )
                }
            }
        )

    fun create(driver: SqlDriver = createDriver()): Database = Database(driver)
}
