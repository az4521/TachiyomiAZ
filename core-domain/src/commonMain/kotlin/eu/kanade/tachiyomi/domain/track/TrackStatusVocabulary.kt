package eu.kanade.tachiyomi.domain.track

/**
 * A title's tracking state, independent of the service holding it.
 *
 * Every service numbers its own statuses, and they disagree: MyAnimeList's "plan to read" is 6,
 * AniList's "planning" is 5, Kitsu's is 5 but its "on hold" is 3 where MangaUpdates' "on hold list"
 * is 4, and the self-hosted three do not have the concept at all. `manga_sync.status` stores the
 * *service's* number, so a value is only meaningful next to the `sync_id` beside it.
 */
enum class TrackStatus {
    READING,
    PLANNING,
    COMPLETED,
    PAUSED,
    DROPPED,
    REREADING,
    NONE
}

/**
 * Translates between [TrackStatus] and the number a service stores in `manga_sync.status`.
 *
 * This exists because the two apps were not agreeing. The Android app writes each service's own
 * number, taken from constants inside that service's client; the iOS app wrote nothing at all, so
 * a title tracked there had status 0 on Android and appeared untracked-in-place. Writing the iOS
 * app's own enum instead would have been worse than nothing: its `PLANNING` is 2, and 2 is
 * `COMPLETED` on MyAnimeList, AniList and Kitsu -- a planned title would have read as finished.
 *
 * The numbers below are the constants in the Android trackers, which are in turn each service's
 * own API values. They are not ours to choose, which is exactly why they belong in one place.
 */
object TrackStatusVocabulary {
    /**
     * The service's number for [status], or null when that service has no equivalent.
     *
     * Null rather than a fallback: recording "reading" for a service that cannot express
     * "rereading" is a quiet lie about what the user set, and the caller can decide whether to
     * leave the stored value alone instead.
     */
    fun toRemote(syncId: Int, status: TrackStatus): Int? =
        when (syncId) {
            TrackerIds.MYANIMELIST ->
                when (status) {
                    TrackStatus.READING -> 1
                    TrackStatus.COMPLETED -> 2
                    TrackStatus.PAUSED -> 3
                    TrackStatus.DROPPED -> 4
                    TrackStatus.PLANNING -> 6
                    // MyAnimeList has no rereading state; it is a flag beside the status there.
                    TrackStatus.REREADING -> 1
                    TrackStatus.NONE -> null
                }
            TrackerIds.ANILIST ->
                when (status) {
                    TrackStatus.READING -> 1
                    TrackStatus.COMPLETED -> 2
                    TrackStatus.PAUSED -> 3
                    TrackStatus.DROPPED -> 4
                    TrackStatus.PLANNING -> 5
                    TrackStatus.REREADING -> 6
                    TrackStatus.NONE -> null
                }
            TrackerIds.KITSU ->
                when (status) {
                    TrackStatus.READING -> 1
                    TrackStatus.COMPLETED -> 2
                    TrackStatus.PAUSED -> 3
                    TrackStatus.DROPPED -> 4
                    TrackStatus.PLANNING -> 5
                    TrackStatus.REREADING -> 1
                    TrackStatus.NONE -> null
                }
            TrackerIds.MANGAUPDATES ->
                when (status) {
                    TrackStatus.READING -> 0
                    TrackStatus.PLANNING -> 1
                    TrackStatus.COMPLETED -> 2
                    TrackStatus.DROPPED -> 3
                    TrackStatus.PAUSED -> 4
                    TrackStatus.REREADING -> 0
                    TrackStatus.NONE -> null
                }
            TrackerIds.HIKKA ->
                when (status) {
                    TrackStatus.READING -> 0
                    TrackStatus.COMPLETED -> 1
                    TrackStatus.PAUSED -> 2
                    TrackStatus.DROPPED -> 3
                    TrackStatus.PLANNING -> 4
                    TrackStatus.REREADING -> 5
                    TrackStatus.NONE -> null
                }
            TrackerIds.MANGABAKA ->
                when (status) {
                    TrackStatus.READING -> 0
                    TrackStatus.COMPLETED -> 1
                    TrackStatus.PAUSED -> 2
                    TrackStatus.DROPPED -> 3
                    TrackStatus.PLANNING -> 4
                    TrackStatus.REREADING -> 5
                    TrackStatus.NONE -> null
                }
            TrackerIds.SHIKIMORI ->
                when (status) {
                    TrackStatus.READING -> 1
                    TrackStatus.COMPLETED -> 2
                    TrackStatus.PAUSED -> 3
                    TrackStatus.DROPPED -> 4
                    TrackStatus.PLANNING -> 5
                    TrackStatus.REREADING -> 6
                    TrackStatus.NONE -> null
                }
            // Bangumi numbers these unlike anyone else: reading is 3 and planning is 1.
            TrackerIds.BANGUMI ->
                when (status) {
                    TrackStatus.PLANNING -> 1
                    TrackStatus.COMPLETED -> 2
                    // No rereading state, so it stays "reading" rather than being dropped.
                    TrackStatus.READING, TrackStatus.REREADING -> 3
                    TrackStatus.PAUSED -> 4
                    TrackStatus.DROPPED -> 5
                    TrackStatus.NONE -> null
                }
            // The self-hosted services track progress through the server itself and express only
            // how far through a series the reader is.
            TrackerIds.KOMGA, TrackerIds.KAVITA, TrackerIds.SUWAYOMI ->
                when (status) {
                    TrackStatus.READING, TrackStatus.REREADING -> 2
                    TrackStatus.COMPLETED -> 3
                    TrackStatus.PLANNING -> 1
                    TrackStatus.PAUSED, TrackStatus.DROPPED, TrackStatus.NONE -> null
                }
            else -> null
        }

    /**
     * The [TrackStatus] a service's stored number means, or [TrackStatus.NONE] when it means
     * nothing this app models.
     */
    fun fromRemote(syncId: Int, raw: Int): TrackStatus =
        when (syncId) {
            TrackerIds.MYANIMELIST ->
                when (raw) {
                    1 -> TrackStatus.READING
                    2 -> TrackStatus.COMPLETED
                    3 -> TrackStatus.PAUSED
                    4 -> TrackStatus.DROPPED
                    6 -> TrackStatus.PLANNING
                    else -> TrackStatus.NONE
                }
            TrackerIds.ANILIST ->
                when (raw) {
                    1 -> TrackStatus.READING
                    2 -> TrackStatus.COMPLETED
                    3 -> TrackStatus.PAUSED
                    4 -> TrackStatus.DROPPED
                    5 -> TrackStatus.PLANNING
                    6 -> TrackStatus.REREADING
                    else -> TrackStatus.NONE
                }
            TrackerIds.KITSU ->
                when (raw) {
                    1 -> TrackStatus.READING
                    2 -> TrackStatus.COMPLETED
                    3 -> TrackStatus.PAUSED
                    4 -> TrackStatus.DROPPED
                    5 -> TrackStatus.PLANNING
                    else -> TrackStatus.NONE
                }
            TrackerIds.MANGAUPDATES ->
                when (raw) {
                    0 -> TrackStatus.READING
                    1 -> TrackStatus.PLANNING
                    2 -> TrackStatus.COMPLETED
                    3 -> TrackStatus.DROPPED
                    4 -> TrackStatus.PAUSED
                    else -> TrackStatus.NONE
                }
            TrackerIds.HIKKA, TrackerIds.MANGABAKA ->
                when (raw) {
                    0 -> TrackStatus.READING
                    1 -> TrackStatus.COMPLETED
                    2 -> TrackStatus.PAUSED
                    3 -> TrackStatus.DROPPED
                    4 -> TrackStatus.PLANNING
                    5 -> TrackStatus.REREADING
                    else -> TrackStatus.NONE
                }
            TrackerIds.SHIKIMORI ->
                when (raw) {
                    1 -> TrackStatus.READING
                    2 -> TrackStatus.COMPLETED
                    3 -> TrackStatus.PAUSED
                    4 -> TrackStatus.DROPPED
                    5 -> TrackStatus.PLANNING
                    6 -> TrackStatus.REREADING
                    else -> TrackStatus.NONE
                }
            TrackerIds.BANGUMI ->
                when (raw) {
                    1 -> TrackStatus.PLANNING
                    2 -> TrackStatus.COMPLETED
                    3 -> TrackStatus.READING
                    4 -> TrackStatus.PAUSED
                    5 -> TrackStatus.DROPPED
                    else -> TrackStatus.NONE
                }
            TrackerIds.KOMGA, TrackerIds.KAVITA, TrackerIds.SUWAYOMI ->
                when (raw) {
                    1 -> TrackStatus.PLANNING
                    2 -> TrackStatus.READING
                    3 -> TrackStatus.COMPLETED
                    else -> TrackStatus.NONE
                }
            else -> TrackStatus.NONE
        }
}
