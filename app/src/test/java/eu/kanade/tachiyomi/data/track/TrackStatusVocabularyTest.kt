package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import eu.kanade.tachiyomi.domain.track.TrackStatus
import eu.kanade.tachiyomi.domain.track.TrackStatusVocabulary
import eu.kanade.tachiyomi.domain.track.TrackerIds
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Holds the shared status vocabulary to the numbers the trackers actually send.
 *
 * Each service's constants are its own API values: `track.status` goes into the request body as it
 * stands, so they cannot be replaced with a call into `:core-domain` without changing what goes
 * over the wire. What can be shared is the *agreement* -- the other app reads and writes the same
 * `manga_sync.status` column and has no access to these classes, so it carries its own copy of
 * these numbers in [TrackStatusVocabulary].
 *
 * Two copies of a set of numbers is the shape of every silent bug this port has turned up, so the
 * copies are checked against each other here rather than by hand. Changing a constant in a tracker
 * without changing the shared vocabulary now fails the build.
 *
 * Nothing here constructs a tracker: the constants are `const`, so they inline and no Android
 * runtime is involved.
 */
class TrackStatusVocabularyTest {
    private fun assertMaps(
        syncId: Int,
        status: TrackStatus,
        expected: Int
    ) {
        assertThat(TrackStatusVocabulary.toRemote(syncId, status))
            .describedAs("%s on service %d", status, syncId)
            .isEqualTo(expected)
        assertThat(TrackStatusVocabulary.fromRemote(syncId, expected))
            .describedAs("service %d value %d", syncId, expected)
            .isEqualTo(status)
    }

    @Test
    fun `myanimelist agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.MYANIMELIST, TrackStatus.READING, MyAnimeList.READING)
        assertMaps(TrackerIds.MYANIMELIST, TrackStatus.COMPLETED, MyAnimeList.COMPLETED)
        assertMaps(TrackerIds.MYANIMELIST, TrackStatus.PAUSED, MyAnimeList.ON_HOLD)
        assertMaps(TrackerIds.MYANIMELIST, TrackStatus.DROPPED, MyAnimeList.DROPPED)
        assertMaps(TrackerIds.MYANIMELIST, TrackStatus.PLANNING, MyAnimeList.PLAN_TO_READ)
    }

    @Test
    fun `anilist agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.ANILIST, TrackStatus.READING, Anilist.READING)
        assertMaps(TrackerIds.ANILIST, TrackStatus.COMPLETED, Anilist.COMPLETED)
        assertMaps(TrackerIds.ANILIST, TrackStatus.PAUSED, Anilist.PAUSED)
        assertMaps(TrackerIds.ANILIST, TrackStatus.DROPPED, Anilist.DROPPED)
        assertMaps(TrackerIds.ANILIST, TrackStatus.PLANNING, Anilist.PLANNING)
        assertMaps(TrackerIds.ANILIST, TrackStatus.REREADING, Anilist.REPEATING)
    }

    @Test
    fun `kitsu agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.KITSU, TrackStatus.READING, Kitsu.READING)
        assertMaps(TrackerIds.KITSU, TrackStatus.COMPLETED, Kitsu.COMPLETED)
        assertMaps(TrackerIds.KITSU, TrackStatus.PAUSED, Kitsu.ON_HOLD)
        assertMaps(TrackerIds.KITSU, TrackStatus.DROPPED, Kitsu.DROPPED)
        assertMaps(TrackerIds.KITSU, TrackStatus.PLANNING, Kitsu.PLAN_TO_READ)
    }

    @Test
    fun `shikimori agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.SHIKIMORI, TrackStatus.READING, Shikimori.READING)
        assertMaps(TrackerIds.SHIKIMORI, TrackStatus.COMPLETED, Shikimori.COMPLETED)
        assertMaps(TrackerIds.SHIKIMORI, TrackStatus.PAUSED, Shikimori.ON_HOLD)
        assertMaps(TrackerIds.SHIKIMORI, TrackStatus.DROPPED, Shikimori.DROPPED)
        assertMaps(TrackerIds.SHIKIMORI, TrackStatus.PLANNING, Shikimori.PLANNING)
        assertMaps(TrackerIds.SHIKIMORI, TrackStatus.REREADING, Shikimori.REPEATING)
    }

    /** Bangumi numbers these unlike anyone else -- reading is 3, planning is 1. */
    @Test
    fun `bangumi agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.BANGUMI, TrackStatus.READING, Bangumi.READING)
        assertMaps(TrackerIds.BANGUMI, TrackStatus.COMPLETED, Bangumi.COMPLETED)
        assertMaps(TrackerIds.BANGUMI, TrackStatus.PAUSED, Bangumi.ON_HOLD)
        assertMaps(TrackerIds.BANGUMI, TrackStatus.DROPPED, Bangumi.DROPPED)
        assertMaps(TrackerIds.BANGUMI, TrackStatus.PLANNING, Bangumi.PLANNING)
    }

    @Test
    fun `mangaupdates agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.MANGAUPDATES, TrackStatus.READING, MangaUpdates.READING_LIST)
        assertMaps(TrackerIds.MANGAUPDATES, TrackStatus.PLANNING, MangaUpdates.WISH_LIST)
        assertMaps(TrackerIds.MANGAUPDATES, TrackStatus.COMPLETED, MangaUpdates.COMPLETE_LIST)
        assertMaps(TrackerIds.MANGAUPDATES, TrackStatus.DROPPED, MangaUpdates.UNFINISHED_LIST)
        assertMaps(TrackerIds.MANGAUPDATES, TrackStatus.PAUSED, MangaUpdates.ON_HOLD_LIST)
    }

    @Test
    fun `hikka agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.HIKKA, TrackStatus.READING, Hikka.READING)
        assertMaps(TrackerIds.HIKKA, TrackStatus.COMPLETED, Hikka.COMPLETED)
        assertMaps(TrackerIds.HIKKA, TrackStatus.PAUSED, Hikka.ON_HOLD)
        assertMaps(TrackerIds.HIKKA, TrackStatus.DROPPED, Hikka.DROPPED)
        assertMaps(TrackerIds.HIKKA, TrackStatus.PLANNING, Hikka.PLAN_TO_READ)
        assertMaps(TrackerIds.HIKKA, TrackStatus.REREADING, Hikka.REREADING)
    }

    @Test
    fun `mangabaka agrees with the shared vocabulary`() {
        assertMaps(TrackerIds.MANGABAKA, TrackStatus.READING, MangaBaka.READING)
        assertMaps(TrackerIds.MANGABAKA, TrackStatus.COMPLETED, MangaBaka.COMPLETED)
        assertMaps(TrackerIds.MANGABAKA, TrackStatus.PAUSED, MangaBaka.PAUSED)
        assertMaps(TrackerIds.MANGABAKA, TrackStatus.DROPPED, MangaBaka.DROPPED)
        assertMaps(TrackerIds.MANGABAKA, TrackStatus.PLANNING, MangaBaka.PLAN_TO_READ)
        assertMaps(TrackerIds.MANGABAKA, TrackStatus.REREADING, MangaBaka.REREADING)
    }

    /**
     * The self-hosted three share one numbering, and express only how far through a series the
     * reader is -- there is no paused or dropped on the server to write to.
     */
    @Test
    fun `the self-hosted services agree with the shared vocabulary`() {
        for (syncId in listOf(TrackerIds.KOMGA, TrackerIds.KAVITA, TrackerIds.SUWAYOMI)) {
            assertMaps(syncId, TrackStatus.READING, Komga.READING)
            assertMaps(syncId, TrackStatus.COMPLETED, Komga.COMPLETED)

            assertThat(TrackStatusVocabulary.toRemote(syncId, TrackStatus.PAUSED)).isNull()
            assertThat(TrackStatusVocabulary.toRemote(syncId, TrackStatus.DROPPED)).isNull()
        }

        // They are only interchangeable above because the three declare the same numbers.
        assertThat(Kavita.READING).isEqualTo(Komga.READING)
        assertThat(Suwayomi.READING).isEqualTo(Komga.READING)
        assertThat(Kavita.COMPLETED).isEqualTo(Komga.COMPLETED)
        assertThat(Suwayomi.COMPLETED).isEqualTo(Komga.COMPLETED)
    }

    /** The ids themselves, which name the service inside `manga_sync` and inside every backup. */
    @Test
    fun `the tracker ids are the shared ones`() {
        assertThat(TrackManager.MYANIMELIST).isEqualTo(TrackerIds.MYANIMELIST)
        assertThat(TrackManager.ANILIST).isEqualTo(TrackerIds.ANILIST)
        assertThat(TrackManager.KITSU).isEqualTo(TrackerIds.KITSU)
        assertThat(TrackManager.SHIKIMORI).isEqualTo(TrackerIds.SHIKIMORI)
        assertThat(TrackManager.BANGUMI).isEqualTo(TrackerIds.BANGUMI)
        assertThat(TrackManager.MANGAUPDATES).isEqualTo(TrackerIds.MANGAUPDATES)
        assertThat(TrackManager.HIKKA).isEqualTo(TrackerIds.HIKKA)
        assertThat(TrackManager.MANGABAKA).isEqualTo(TrackerIds.MANGABAKA)
        assertThat(TrackManager.KOMGA).isEqualTo(TrackerIds.KOMGA)
        assertThat(TrackManager.KAVITA).isEqualTo(TrackerIds.KAVITA)
        assertThat(TrackManager.SUWAYOMI).isEqualTo(TrackerIds.SUWAYOMI)
    }
}
