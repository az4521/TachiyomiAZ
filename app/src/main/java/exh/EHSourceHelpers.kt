package exh

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.english.HentaiCafe
import eu.kanade.tachiyomi.source.online.english.Pururin
import eu.kanade.tachiyomi.source.online.english.Tsumino

/**
 * Source helpers
 */

// Lewd source IDs
const val LEWD_SOURCE_SERIES = 6900L
const val EH_SOURCE_ID = LEWD_SOURCE_SERIES + 1
const val EXH_SOURCE_ID = LEWD_SOURCE_SERIES + 2
const val NHENTAI_SOURCE_ID = LEWD_SOURCE_SERIES + 7
val HENTAI_CAFE_SOURCE_ID = delegatedSourceId<HentaiCafe>()
val PURURIN_SOURCE_ID = delegatedSourceId<Pururin>()
val TSUMINO_SOURCE_ID = delegatedSourceId<Tsumino>()
const val MERGED_SOURCE_ID = LEWD_SOURCE_SERIES + 69

// Un-Lewded source IDs
const val EIGHTMUSES_SOURCE_ID = 1802675169972965535
const val HITOMI_SOURCE_ID = 690123758188633713

private val DELEGATED_LEWD_SOURCES =
    listOf(
        HentaiCafe::class,
        Pururin::class,
        Tsumino::class
    )

val LIBRARY_UPDATE_EXCLUDED_SOURCES =
    listOf(
        EH_SOURCE_ID,
        EXH_SOURCE_ID
    )

private inline fun <reified T> delegatedSourceId(): Long {
    return SourceManager.DELEGATED_SOURCES.entries.find {
        it.value.newSourceClass == T::class
    }!!.value.sourceId
}

// Used to speed up isLewdSource
val lewdDelegatedSourceIds =
    SourceManager.DELEGATED_SOURCES.filter {
        it.value.newSourceClass in DELEGATED_LEWD_SOURCES
    }.map { it.value.sourceId }.sorted()

// This method MUST be fast!
fun isLewdSource(source: Long) =
    source in 6900..6999 ||
        lewdDelegatedSourceIds.binarySearch(source) >= 0

fun Source.isEhBasedSource() = id == EH_SOURCE_ID || id == EXH_SOURCE_ID

fun Source.isNamespaceSource() =
    id == EH_SOURCE_ID || id == EXH_SOURCE_ID || id == NHENTAI_SOURCE_ID || id == PURURIN_SOURCE_ID || id == TSUMINO_SOURCE_ID
