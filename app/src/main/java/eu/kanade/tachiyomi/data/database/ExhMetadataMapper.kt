package eu.kanade.tachiyomi.data.database

import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle

fun mapSearchMetadata(
    mangaId: Long,
    uploader: String?,
    extra: String,
    indexedExtra: String?,
    extraVersion: Long
): SearchMetadata = SearchMetadata(mangaId, uploader, extra, indexedExtra, extraVersion.toInt())

fun mapSearchTag(
    id: Long,
    mangaId: Long,
    namespace: String?,
    name: String,
    type: Long
): SearchTag = SearchTag(id, mangaId, namespace, name, type.toInt())

fun mapSearchTitle(
    id: Long,
    mangaId: Long,
    title: String,
    type: Long
): SearchTitle = SearchTitle(id, mangaId, title, type.toInt())
