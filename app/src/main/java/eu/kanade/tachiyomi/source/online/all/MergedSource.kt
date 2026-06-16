package eu.kanade.tachiyomi.source.online.all

import android.util.Log
import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.MERGED_SOURCE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

// TODO LocalSource compatibility
// TODO Disable clear database option
class MergedSource : HttpSource() {
    private val db: DatabaseHelper by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    private val json: Json by injectLazy()

    override val id: Long = MERGED_SOURCE_ID

    override val baseUrl = ""

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList
    ) = throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val loaded = readMangaConfig(manga).load(db, sourceManager).first()
        return SManga.create().apply {
            this.copyFrom(loaded.manga)
            url = manga.url
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        coroutineScope {
            readMangaConfig(manga).load(db, sourceManager)
                .toList()
                .map { loadedManga ->
                    async {
                        loadedManga.source.getMangaUpdate(loadedManga.manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters.map { chapter ->
                            chapter.apply {
                                url = writeUrlConfig(UrlConfig(loadedManga.source.id, url, loadedManga.manga.url))
                            }
                        }
                    }
                }
                .map { it.await() }
                .flatten()
        }
    }

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val config = readUrlConfig(chapter.url)
        val source = sourceManager.getOrStub(config.source)
        val pages = source.getPageList(
            SChapter.create().apply {
                copyFrom(chapter)
                url = config.url
            }
        )
        return pages.map { page ->
            page.copyWithUrl(writeUrlConfig(UrlConfig(config.source, page.url, config.mangaUrl)))
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val config = readUrlConfig(page.url)
        val source = sourceManager.getOrStub(config.source) as? HttpSource
            ?: throw UnsupportedOperationException("This source does not support this operation!")
        return source.getImageUrl(page.copyWithUrl(config.url))
    }

    override fun pageListParse(response: Response) = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getImage(page: Page): Response {
        val config = readUrlConfig(page.url)
        val source = sourceManager.getOrStub(config.source) as? HttpSource
            ?: throw UnsupportedOperationException("This source does not support this operation!")
        return source.getImage(page.copyWithUrl(config.url))
    }

    @Suppress("DEPRECATION")
    @Deprecated("All modifications should be done when constructing the chapter")
    override fun prepareNewChapter(
        chapter: SChapter,
        manga: SManga
    ) {
        val chapterConfig = readUrlConfig(chapter.url)
        val source =
            sourceManager.getOrStub(chapterConfig.source) as? HttpSource
                ?: throw UnsupportedOperationException("This source does not support this operation!")
        val copiedManga =
            SManga.create().apply {
                this.copyFrom(manga)
                url = chapterConfig.mangaUrl
            }
        chapter.url = chapterConfig.url
        source.prepareNewChapter(chapter, copiedManga)
        chapter.url = writeUrlConfig(UrlConfig(source.id, chapter.url, chapterConfig.mangaUrl))
        chapter.scanlator =
            if (chapter.scanlator.isNullOrBlank()) {
                source.name
            } else {
                "$source: ${chapter.scanlator}"
            }
    }

    fun readMangaConfig(manga: SManga): MangaConfig {
        return MangaConfig.readFromUrl(json, manga.url)
    }

    fun readUrlConfig(url: String): UrlConfig {
        return json.decodeFromString(url)
    }

    fun writeUrlConfig(urlConfig: UrlConfig): String {
        return json.encodeToString(urlConfig)
    }

    data class LoadedMangaSource(val source: Source, val manga: Manga)

    @Serializable
    data class MangaSource(
        @SerialName("s")
        val source: Long,
        @SerialName("u")
        val url: String
    ) {
        suspend fun load(
            db: DatabaseHelper,
            sourceManager: SourceManager
        ): LoadedMangaSource? {
            val manga = db.getManga(url, source).executeAsBlocking() ?: return null
            val source = sourceManager.getOrStub(source)
            return LoadedMangaSource(source, manga)
        }
    }

    @Serializable
    data class MangaConfig(
        @SerialName("c")
        val children: List<MangaSource>
    ) {
        fun load(
            db: DatabaseHelper,
            sourceManager: SourceManager
        ): Flow<LoadedMangaSource> {
            return children.asFlow().map { mangaSource ->
                mangaSource.load(db, sourceManager)
                    ?: run {
                        XLog.w("> Missing source manga: $mangaSource")
                        Log.d("MERGED", "> Missing source manga: $mangaSource")
                        throw IllegalStateException("Missing source manga: $mangaSource")
                    }
            }
        }

        fun writeAsUrl(json: Json): String {
            return json.encodeToString(this)
        }

        companion object {
            fun readFromUrl(
                json: Json,
                url: String
            ): MangaConfig {
                return json.decodeFromString(url)
            }
        }
    }

    @Serializable
    data class UrlConfig(
        @SerialName("s")
        val source: Long,
        @SerialName("u")
        val url: String,
        @SerialName("m")
        val mangaUrl: String
    )

    fun Page.copyWithUrl(newUrl: String) =
        Page(
            index,
            newUrl,
            imageUrl,
            uri
        )

    override val lang = "all"
    override val supportsLatest = false
    override val name = "MergedSource"
}
