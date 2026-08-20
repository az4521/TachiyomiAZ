package eu.kanade.tachiyomi.domain.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a stored status means depends entirely on which service stored it, which is the reason this
 * table exists rather than a single enum written straight to the column.
 */
class TrackStatusVocabularyTest {
    @Test
    fun `the same number means different things to different services`() {
        // 2 is "completed" on MyAnimeList and "reading" on Komga.
        assertEquals(TrackStatus.COMPLETED, TrackStatusVocabulary.fromRemote(TrackerIds.MYANIMELIST, 2))
        assertEquals(TrackStatus.READING, TrackStatusVocabulary.fromRemote(TrackerIds.KOMGA, 2))

        // 5 is "planning" on AniList and "rereading" on Hikka.
        assertEquals(TrackStatus.PLANNING, TrackStatusVocabulary.fromRemote(TrackerIds.ANILIST, 5))
        assertEquals(TrackStatus.REREADING, TrackStatusVocabulary.fromRemote(TrackerIds.HIKKA, 5))
    }

    /**
     * The mistake this table is here to prevent: writing one app's own enum value into the column.
     * The iOS app numbers PLANNING as 2, and 2 is COMPLETED on the three biggest services -- a
     * planned title would have come back finished.
     */
    @Test
    fun `planning is not two on the services that call two completed`() {
        for (service in listOf(TrackerIds.MYANIMELIST, TrackerIds.ANILIST, TrackerIds.KITSU)) {
            assertTrue(TrackStatusVocabulary.toRemote(service, TrackStatus.PLANNING) != 2)
            assertEquals(TrackStatus.COMPLETED, TrackStatusVocabulary.fromRemote(service, 2))
        }
    }

    @Test
    fun `every status a service supports survives a round trip`() {
        val services =
            listOf(
                TrackerIds.MYANIMELIST,
                TrackerIds.ANILIST,
                TrackerIds.KITSU,
                TrackerIds.MANGAUPDATES,
                TrackerIds.HIKKA,
                TrackerIds.MANGABAKA
            )
        // Rereading is excluded: three of these have no such state and deliberately fold it into
        // reading, which the next test pins.
        val statuses =
            listOf(
                TrackStatus.READING,
                TrackStatus.PLANNING,
                TrackStatus.COMPLETED,
                TrackStatus.PAUSED,
                TrackStatus.DROPPED
            )

        for (service in services) {
            for (status in statuses) {
                val raw = TrackStatusVocabulary.toRemote(service, status)
                requireNotNull(raw) { "service $service cannot express $status" }
                assertEquals(status, TrackStatusVocabulary.fromRemote(service, raw), "service $service, $status")
            }
        }
    }

    @Test
    fun `rereading folds into reading only where the service has no word for it`() {
        // AniList and Hikka have one.
        assertEquals(6, TrackStatusVocabulary.toRemote(TrackerIds.ANILIST, TrackStatus.REREADING))
        assertEquals(5, TrackStatusVocabulary.toRemote(TrackerIds.HIKKA, TrackStatus.REREADING))
        // MyAnimeList and Kitsu do not, so it reports as reading rather than as nothing.
        assertEquals(1, TrackStatusVocabulary.toRemote(TrackerIds.MYANIMELIST, TrackStatus.REREADING))
        assertEquals(1, TrackStatusVocabulary.toRemote(TrackerIds.KITSU, TrackStatus.REREADING))
    }

    @Test
    fun `the self-hosted services express only progress`() {
        for (service in listOf(TrackerIds.KOMGA, TrackerIds.KAVITA, TrackerIds.SUWAYOMI)) {
            assertEquals(2, TrackStatusVocabulary.toRemote(service, TrackStatus.READING))
            assertEquals(3, TrackStatusVocabulary.toRemote(service, TrackStatus.COMPLETED))
            // A server that only knows unread, reading and read cannot hold these.
            assertNull(TrackStatusVocabulary.toRemote(service, TrackStatus.PAUSED))
            assertNull(TrackStatusVocabulary.toRemote(service, TrackStatus.DROPPED))
        }
    }

    /**
     * This test used to assert the opposite -- that Shikimori and Bangumi had no vocabulary and
     * reported none. That was wrong: both declare status constants in the Android trackers, and
     * treating them as absent meant this app wrote no status for either, the very gap the rest of
     * this file exists to close. `:app`'s TrackStatusVocabularyTest now checks these against the
     * trackers' own constants, so the claim is no longer made from memory.
     */
    @Test
    fun `shikimori and bangumi have vocabularies of their own`() {
        assertEquals(1, TrackStatusVocabulary.toRemote(TrackerIds.SHIKIMORI, TrackStatus.READING))
        assertEquals(6, TrackStatusVocabulary.toRemote(TrackerIds.SHIKIMORI, TrackStatus.REREADING))
        assertEquals(TrackStatus.PLANNING, TrackStatusVocabulary.fromRemote(TrackerIds.SHIKIMORI, 5))

        // Bangumi numbers these unlike anyone else: reading is 3 and planning is 1, where every
        // other service reads 1 as "reading".
        assertEquals(3, TrackStatusVocabulary.toRemote(TrackerIds.BANGUMI, TrackStatus.READING))
        assertEquals(1, TrackStatusVocabulary.toRemote(TrackerIds.BANGUMI, TrackStatus.PLANNING))
        assertEquals(TrackStatus.PLANNING, TrackStatusVocabulary.fromRemote(TrackerIds.BANGUMI, 1))
        assertEquals(TrackStatus.READING, TrackStatusVocabulary.fromRemote(TrackerIds.BANGUMI, 3))
    }

    @Test
    fun `an unknown service reports none rather than guessing`() {
        assertNull(TrackStatusVocabulary.toRemote(syncId = 999, status = TrackStatus.READING))
        assertEquals(TrackStatus.NONE, TrackStatusVocabulary.fromRemote(syncId = 999, raw = 1))
    }

    @Test
    fun `tracker ids round-trip by name and identify the self-hosted three`() {
        assertEquals(TrackerIds.SUWAYOMI, TrackerIds.syncId("suwayomi"))
        assertEquals("komga", TrackerIds.name(TrackerIds.KOMGA))
        assertNull(TrackerIds.syncId("nonesuch"))

        assertTrue(TrackerIds.usesTrackingUrlAsId(TrackerIds.KOMGA))
        assertTrue(TrackerIds.usesTrackingUrlAsId(TrackerIds.KAVITA))
        assertTrue(TrackerIds.usesTrackingUrlAsId(TrackerIds.SUWAYOMI))
        assertTrue(!TrackerIds.usesTrackingUrlAsId(TrackerIds.MYANIMELIST))
    }
}
