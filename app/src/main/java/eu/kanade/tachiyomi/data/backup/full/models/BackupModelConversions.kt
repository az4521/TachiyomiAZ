package eu.kanade.tachiyomi.data.backup.full.models

import eu.kanade.tachiyomi.data.backup.full.models.metadata.BackupSearchMetadata
import eu.kanade.tachiyomi.data.backup.full.models.metadata.BackupSearchTag
import eu.kanade.tachiyomi.data.backup.full.models.metadata.BackupSearchTitle
import eu.kanade.tachiyomi.source.Source
import exh.metadata.metadata.base.FlatMetadata
import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle

/**
 * Conversions between the shared backup models and the types they map to on this platform.
 *
 * The models themselves live in :core-domain, because the wire format -- the @ProtoNumber
 * allocation -- is the thing both apps must agree on byte for byte. What cannot be shared is
 * either side of these conversions: [Source] is the extension API, and the exh search-metadata
 * models are an Android-only subsystem. Keeping them here means the format stays portable while
 * the mapping stays platform-specific.
 */

fun BackupSource.Companion.copyFrom(source: Source): BackupSource =
    BackupSource(
        name = source.name,
        sourceId = source.id
    )

fun BackupFlatMetadata.getFlatMetadata(mangaId: Long): FlatMetadata =
    FlatMetadata(
        metadata = searchMetadata.getSearchMetadata(mangaId),
        tags = searchTags.map { it.getSearchTag(mangaId) },
        titles = searchTitles.map { it.getSearchTitle(mangaId) }
    )

fun BackupFlatMetadata.Companion.copyFrom(flatMetadata: FlatMetadata): BackupFlatMetadata =
    BackupFlatMetadata(
        searchMetadata = BackupSearchMetadata.copyFrom(flatMetadata.metadata),
        searchTags = flatMetadata.tags.map { BackupSearchTag.copyFrom(it) },
        searchTitles = flatMetadata.titles.map { BackupSearchTitle.copyFrom(it) }
    )

fun BackupSearchMetadata.getSearchMetadata(mangaId: Long): SearchMetadata =
    SearchMetadata(
        mangaId = mangaId,
        uploader = uploader,
        extra = extra,
        indexedExtra = indexedExtra,
        extraVersion = extraVersion
    )

fun BackupSearchMetadata.Companion.copyFrom(searchMetadata: SearchMetadata): BackupSearchMetadata =
    BackupSearchMetadata(
        uploader = searchMetadata.uploader,
        extra = searchMetadata.extra,
        indexedExtra = searchMetadata.indexedExtra,
        extraVersion = searchMetadata.extraVersion
    )

fun BackupSearchTag.getSearchTag(mangaId: Long): SearchTag =
    SearchTag(
        id = null,
        mangaId = mangaId,
        namespace = namespace,
        name = name,
        type = type
    )

fun BackupSearchTag.Companion.copyFrom(searchTag: SearchTag): BackupSearchTag =
    BackupSearchTag(
        namespace = searchTag.namespace,
        name = searchTag.name,
        type = searchTag.type
    )

fun BackupSearchTitle.getSearchTitle(mangaId: Long): SearchTitle =
    SearchTitle(
        id = null,
        mangaId = mangaId,
        title = title,
        type = type
    )

fun BackupSearchTitle.Companion.copyFrom(searchTitle: SearchTitle): BackupSearchTitle =
    BackupSearchTitle(
        title = searchTitle.title,
        type = searchTitle.type
    )
