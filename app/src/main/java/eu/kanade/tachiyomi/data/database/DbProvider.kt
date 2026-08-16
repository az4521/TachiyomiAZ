package eu.kanade.tachiyomi.data.database

import com.pushtorefresh.storio.sqlite.impl.DefaultStorIOSQLite

interface DbProvider {
    val db: DefaultStorIOSQLite

    /**
     * Typed queries generated from app/src/main/sqldelight. Query interfaces migrate onto this
     * one at a time; both read the same database through the same open helper.
     */
    val sqlDatabase: Database
}
