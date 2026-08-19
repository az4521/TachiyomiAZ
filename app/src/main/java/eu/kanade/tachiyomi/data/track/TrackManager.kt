package eu.kanade.tachiyomi.data.track

import android.content.Context
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
import eu.kanade.tachiyomi.domain.track.TrackerIds

class TrackManager(context: Context) {
    /**
     * The `sync_id` of each service, from [TrackerIds].
     *
     * These are kept as names here because the call sites read better for it, but the numbers are
     * no longer written down twice: they identify a service inside `manga_sync` and inside every
     * backup, so the other app has to agree with them. Being `const`, a drift between the two
     * lists is now a compile error rather than something noticed when a tracked title comes back
     * from a backup attached to the wrong service.
     */
    companion object {
        const val MYANIMELIST = TrackerIds.MYANIMELIST
        const val ANILIST = TrackerIds.ANILIST
        const val KITSU = TrackerIds.KITSU
        const val SHIKIMORI = TrackerIds.SHIKIMORI
        const val BANGUMI = TrackerIds.BANGUMI
        const val MANGAUPDATES = TrackerIds.MANGAUPDATES
        const val HIKKA = TrackerIds.HIKKA
        const val MANGABAKA = TrackerIds.MANGABAKA
        const val KOMGA = TrackerIds.KOMGA
        const val KAVITA = TrackerIds.KAVITA
        const val SUWAYOMI = TrackerIds.SUWAYOMI
    }

    val myAnimeList = MyAnimeList(context, MYANIMELIST)

    val aniList = Anilist(context, ANILIST)

    val kitsu = Kitsu(context, KITSU)

    val shikimori = Shikimori(context, SHIKIMORI)

    val bangumi = Bangumi(context, BANGUMI)

    val mangaUpdates = MangaUpdates(context, MANGAUPDATES)

    val hikka = Hikka(context, HIKKA)

    val mangaBaka = MangaBaka(context, MANGABAKA)

    val komga = Komga(context, KOMGA)

    val kavita = Kavita(context, KAVITA)

    val suwayomi = Suwayomi(context, SUWAYOMI)

    val services =
        listOf(
            myAnimeList,
            aniList,
            kitsu,
            shikimori,
            bangumi,
            mangaUpdates,
            hikka,
            mangaBaka,
            komga,
            kavita,
            suwayomi
        )

    fun getService(id: Int) = services.find { it.id == id }

    fun hasLoggedServices() = services.any { it.isLogged }

    fun countLoggedServices() = services.count { it.isLogged }
}
