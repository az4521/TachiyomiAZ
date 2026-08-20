package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.TrackImpl

@Suppress("LongParameterList")
fun mapTrack(
    id: Long,
    mangaId: Long,
    syncId: Long,
    remoteId: Long,
    libraryId: Long?,
    title: String,
    lastChapterRead: Long,
    totalChapters: Long,
    status: Long,
    score: Double,
    remoteUrl: String,
    startDate: Long,
    finishDate: Long,
    private: Long
): Track =
    TrackImpl().also {
        it.id = id
        it.manga_id = mangaId
        it.sync_id = syncId.toInt()
        it.media_id = remoteId
        it.library_id = libraryId
        it.title = title
        it.last_chapter_read = lastChapterRead.toInt()
        it.total_chapters = totalChapters.toInt()
        it.status = status.toInt()
        it.score = score.toFloat()
        it.tracking_url = remoteUrl
        it.started_reading_date = startDate
        it.finished_reading_date = finishDate
        it.private = private == 1L
    }
