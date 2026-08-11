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

class TrackManager(context: Context) {
    companion object {
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
