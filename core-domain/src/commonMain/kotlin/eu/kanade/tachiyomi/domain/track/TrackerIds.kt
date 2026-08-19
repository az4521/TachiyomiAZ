package eu.kanade.tachiyomi.domain.track

/**
 * The `sync_id` each tracking service is stored under in `manga_sync`.
 *
 * These numbers identify a service across both apps and inside every backup, so they are not free
 * to change and not free to guess. They existed twice -- as constants in Android's `TrackManager`
 * and as a dictionary in iOS's `TrackerSyncId` -- with nothing but attention keeping the two lists
 * the same. A title tracked on one app is only recognised by the other because they agree here.
 */
object TrackerIds {
    const val MYANIMELIST = 1
    const val ANILIST = 2
    const val KITSU = 3
    const val SHIKIMORI = 4
    const val BANGUMI = 5
    const val MANGAUPDATES = 6
    const val HIKKA = 7
    const val MANGABAKA = 8
    const val KOMGA = 9
    const val KAVITA = 10
    const val SUWAYOMI = 11

    /**
     * The lowercase name each id is known by outside the database -- what the iOS trackers call
     * themselves, and what a backup's tracker entry carries.
     */
    private val names =
        mapOf(
            "myanimelist" to MYANIMELIST,
            "anilist" to ANILIST,
            "kitsu" to KITSU,
            "shikimori" to SHIKIMORI,
            "bangumi" to BANGUMI,
            "mangaupdates" to MANGAUPDATES,
            "hikka" to HIKKA,
            "mangabaka" to MANGABAKA,
            "komga" to KOMGA,
            "kavita" to KAVITA,
            "suwayomi" to SUWAYOMI
        )

    fun syncId(name: String): Int? = names[name.lowercase()]

    fun name(syncId: Int): String? = names.entries.firstOrNull { it.value == syncId }?.key

    /**
     * The services that track against the server a title came from.
     *
     * They identify a title by a composite string -- the source key and the series id -- which
     * does not fit the integer `media_id` column, so both apps key them on `tracking_url` instead.
     * Android's Komga tracker matches with `track.tracking_url == manga.url`; iOS follows it.
     */
    private val enhanced = setOf(KOMGA, KAVITA, SUWAYOMI)

    fun usesTrackingUrlAsId(syncId: Int): Boolean = syncId in enhanced
}
