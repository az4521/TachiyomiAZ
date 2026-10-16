package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slowdowns the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 *
 * @param context the application context.
 * @param provider the downloads directories provider.
 * @param sourceManager the source manager.
 * @param preferences the preferences of the app.
 */
class DownloadCache(
    private val context: Context,
    private val provider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val preferences: PreferencesHelper = Injekt.get()
) {
    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = TimeUnit.HOURS.toMillis(1)

    /**
     * The last time the cache was refreshed.
     */
    private var lastRenew = 0L

    /**
     * The root directory for downloads.
     */
    private var rootDir = RootDirectory(getDirectoryFromPreference())

    init {
        preferences.downloadsDirectory().asFlow()
            .onEach {
                lastRenew = 0L // invalidate cache
                rootDir = RootDirectory(getDirectoryFromPreference())
            }
    }

    /**
     * Returns the downloads directory from the user's preferences.
     */
    private fun getDirectoryFromPreference(): UniFile {
        val dir = preferences.downloadsDirectory().get()
        return UniFile.fromUri(context, Uri.parse(dir))
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapter the chapter to check.
     * @param manga the manga of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapter: Chapter,
        manga: Manga,
        skipCache: Boolean
    ): Boolean {
        if (skipCache) {
            val source = sourceManager.get(manga.source) ?: return false
            return provider.findChapterDir(chapter, manga, source) != null
        }

        checkRenew()

        val sourceDir = rootDir.files[manga.source] ?: return false
        val chapterDirNames = provider.getValidChapterDirNames(chapter)
        return sourceDir.mangaDirsOf(manga).any { mangaDir ->
            chapterDirNames.any { it in mangaDir.files }
        }
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getDownloadCount(): Int {
        checkRenew()

        return rootDir.files.map { source ->
            source.value.files.map { manga ->
                manga.value.files.filter { !it.endsWith(Downloader.TMP_DIR_SUFFIX) }.size
            }.sum() ?: 0
        }.sum() ?: 0
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        checkRenew()

        val sourceDir = rootDir.files[manga.source] ?: return 0
        return sourceDir.mangaDirsOf(manga)
            .flatMapTo(HashSet()) { it.files }
            .count { !it.endsWith(Downloader.TMP_DIR_SUFFIX) }
    }

    /**
     * Checks if the cache needs a renewal and performs it if needed.
     */
    @Synchronized
    private fun checkRenew() {
        if (lastRenew + renewInterval < System.currentTimeMillis()) {
            renew()
            lastRenew = System.currentTimeMillis()
        }
    }

    /**
     * Renews the downloads cache.
     */
    private fun renew() {
        // SY -->
        val onlineSources = sourceManager.getVisibleOnlineSources()
        // SY <--

        // A source has a folder for each filename setting it was downloaded under, so every
        // valid name maps to it, and its folders are merged into one entry.
        val sourceIdsByDirName =
            onlineSources
                .flatMap { source -> provider.getValidSourceDirNames(source).map { it to source.id } }
                .toMap()

        rootDir.files =
            rootDir.dir.listFiles()
                .orEmpty()
                .groupBy { sourceIdsByDirName[it.name] }
                .mapNotNullKeys { it.key }
                .mapValues { (_, dirs) -> SourceDirectory(readMangaDirs(dirs)) }
    }

    /**
     * Reads the manga folders under [sourceDirs], merging folders of the same name.
     */
    private fun readMangaDirs(sourceDirs: List<UniFile>): Map<String, MangaDirectory> {
        val mangaDirs = HashMap<String, MangaDirectory>()
        sourceDirs.forEach { sourceDir ->
            sourceDir.listFiles().orEmpty().forEach { mangaDir ->
                val name = mangaDir.name ?: return@forEach
                val chapterDirs = mangaDir.listFiles().orEmpty().mapNotNullTo(HashSet()) { it.name }
                val cached = mangaDirs.getOrPut(name) { MangaDirectory() }
                cached.files += chapterDirs
            }
        }
        return mangaDirs
    }

    /**
     * Returns the cached folders of [manga], one for each filename setting it was downloaded under.
     */
    private fun SourceDirectory.mangaDirsOf(manga: Manga): List<MangaDirectory> {
        return provider.getValidMangaDirNames(manga).distinct().mapNotNull { files[it] }
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    @Synchronized
    fun addChapter(
        chapterDirName: String,
        mangaUniFile: UniFile,
        manga: Manga
    ) {
        // Retrieve the cached source directory or cache a new one
        var sourceDir = rootDir.files[manga.source]
        if (sourceDir == null) {
            sourceDir = SourceDirectory()
            rootDir.files += manga.source to sourceDir
        }

        // Retrieve the cached manga directory or cache a new one. Key it by the folder's own
        // name, which may be the one the other filename setting builds.
        val mangaDirName = mangaUniFile.name ?: provider.getMangaDirName(manga)
        var mangaDir = sourceDir.files[mangaDirName]
        if (mangaDir == null) {
            mangaDir = MangaDirectory()
            sourceDir.files += mangaDirName to mangaDir
        }

        // Save the chapter directory
        mangaDir.files += chapterDirName
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    @Synchronized
    fun removeChapter(
        chapter: Chapter,
        manga: Manga
    ) {
        removeChapters(listOf(chapter), manga)
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    @Synchronized
    fun removeChapters(
        chapters: List<Chapter>,
        manga: Manga
    ) {
        val sourceDir = rootDir.files[manga.source] ?: return
        val chapterDirNames = chapters.flatMap { provider.getValidChapterDirNames(it) }
        sourceDir.mangaDirsOf(manga).forEach { mangaDir ->
            mangaDir.files -= chapterDirNames
        }
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    @Synchronized
    fun removeManga(manga: Manga) {
        val sourceDir = rootDir.files[manga.source] ?: return
        sourceDir.files -= provider.getValidMangaDirNames(manga)
    }

    /**
     * Class to store the files under the root downloads directory.
     */
    private class RootDirectory(
        val dir: UniFile,
        var files: Map<Long, SourceDirectory> = hashMapOf()
    )

    /**
     * Class to store the files under a source directory.
     */
    private class SourceDirectory(
        var files: Map<String, MangaDirectory> = hashMapOf()
    )

    /**
     * Class to store the files under a manga directory.
     */
    private class MangaDirectory(
        var files: Set<String> = hashSetOf()
    )

    /**
     * Returns a new map containing only the key entries of [transform] that are not null.
     */
    private inline fun <K, V, R> Map<out K, V>.mapNotNullKeys(transform: (Map.Entry<K?, V>) -> R?): Map<R, V> {
        val destination = LinkedHashMap<R, V>()
        forEach { element -> transform(element)?.let { destination[it] = element.value } }
        return destination
    }
}
