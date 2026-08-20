package eu.kanade.tachiyomi.data.database

interface DbProvider {
    /** Typed queries generated from app/src/main/sqldelight. */
    val sqlDatabase: Database
}
