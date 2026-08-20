package eu.kanade.tachiyomi.data.database

/**
 * The iOS side of [DatabaseHandler].
 *
 * On Android `DatabaseHelper` implements this, carrying the open helper and the Requery factory
 * with it. iOS needs none of that: [IosDatabaseFactory] hands back a `Database` that already owns
 * its schema, so all this has to supply is that instance and a transaction.
 *
 * Every query is a default method on the mixins in `queries`, so implementing [DbProvider] is
 * genuinely all it takes to make the whole shared query surface callable from Swift.
 */
class IosDatabaseHandler(
    override val sqlDatabase: Database
) : DatabaseHandler {
    override fun inTransaction(block: () -> Unit) {
        sqlDatabase.transaction { block() }
    }

    companion object {
        /**
         * Opens the database and wraps it. The one call Swift needs to get started.
         *
         * [name] is a bare file name; put the directory in [directoryPath]. Passing a path in
         * [name] throws, and since this is not `@Throws` the exception kills the process instead
         * of reaching Swift.
         */
        fun open(
            name: String = IosDatabaseFactory.DATABASE_NAME,
            directoryPath: String? = null
        ): IosDatabaseHandler =
            IosDatabaseHandler(
                IosDatabaseFactory.create(IosDatabaseFactory.createDriver(name, directoryPath))
            )
    }
}
