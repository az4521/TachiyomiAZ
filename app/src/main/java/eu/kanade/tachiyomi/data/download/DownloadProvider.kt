package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.injectLazy

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
class DownloadProvider(private val context: Context) {
    private val preferences: PreferencesHelper by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    /**
     * The root directory for downloads.
     */
    private var downloadsDir =
        preferences.downloadsDirectory().get().let {
            val dir = UniFile.fromUri(context, it.toUri())
            DiskUtil.createNoMediaFile(dir, context)
            dir
        }

    init {
        preferences.downloadsDirectory().asFlow()
            .onEach { downloadsDir = UniFile.fromUri(context, it.toUri()) }
            .launchIn(scope)
    }

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param manga the manga to query.
     * @param source the source of the manga.
     */
    internal fun getMangaDir(
        manga: Manga,
        source: Source
    ): UniFile {
        try {
            return downloadsDir
                .createDirectory(getSourceDirName(source))
                .createDirectory(getMangaDirName(manga))
        } catch (_: NullPointerException) {
            throw Exception(context.getString(R.string.invalid_download_dir))
        }
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        return getValidSourceDirNames(source).asSequence()
            .mapNotNull { downloadsDir.findFile(it) }
            .firstOrNull()
    }

    fun findSourceDirs(source: Source): List<UniFile?> {
        return getValidSourceDirNames(source).asSequence()
            .mapNotNull { downloadsDir.findFile(it) }.toList()
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param manga the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(
        manga: Manga,
        source: Source
    ): UniFile? {
        val sourceDirs = findSourceDirs(source)
        val mangaNames = getValidMangaDirNames(manga)

        return mangaNames.asSequence()
            .flatMap {
                    manga ->
                sourceDirs
                    .mapNotNull {
                            source ->
                        source?.findFile(manga)
                    }
            }.firstOrNull()
    }

    fun findMangaDirs(
        manga: Manga,
        source: Source
    ): List<UniFile?> {
        val sourceDirs = findSourceDirs(source)
        val mangaNames = getValidMangaDirNames(manga)

        return mangaNames.asSequence()
            .flatMap {
                    manga ->
                sourceDirs
                    .mapNotNull {
                            source ->
                        source?.findFile(manga)
                    }
            }.toList()
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapter the chapter to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDir(
        chapter: Chapter,
        manga: Manga,
        source: Source
    ): UniFile? {
        val mangaDirs = findMangaDirs(manga, source)
        val chapterNames = getValidChapterDirNames(chapter)

        return chapterNames.asSequence()
            .flatMap {
                    chapter ->
                mangaDirs
                    .mapNotNull {
                            manga ->
                        manga?.findFile(chapter)
                    }
            }.firstOrNull()
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDirs(
        chapters: List<Chapter>,
        manga: Manga,
        source: Source
    ): List<UniFile> {
        return chapters.mapNotNull { chapter ->
            findChapterDir(chapter, manga, source)
        }
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source, disallowNonAscii: Boolean = preferences.disallowNonAsciiFilenames().get()): String {
        return DiskUtil.buildValidFilename(source.toString(), disallowNonAscii = disallowNonAscii)
    }

    fun getValidSourceDirNames(source: Source): List<String> {
        return listOf(
            getSourceDirName(source, true),
            getSourceDirName(source, false)
        )
    }

    /**
     * Returns the download directory name for a manga.
     *
     * @param manga the manga to query.
     */
    fun getMangaDirName(manga: Manga, disallowNonAscii: Boolean = preferences.disallowNonAsciiFilenames().get()): String {
        return DiskUtil.buildValidFilename(manga.title, disallowNonAscii = disallowNonAscii)
    }

    fun getValidMangaDirNames(manga: Manga): List<String> {
        return listOf(
            getMangaDirName(manga, true),
            getMangaDirName(manga, false)
        )
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapter the chapter to query.
     */
    fun getChapterDirName(chapter: Chapter, disallowNonAscii: Boolean = preferences.disallowNonAsciiFilenames().get()): String {
        return DiskUtil.buildValidFilename(
            when {
                chapter.scanlator != null -> "${chapter.scanlator}_${chapter.name}"
                else -> chapter.name
            },
            disallowNonAscii = disallowNonAscii
        )
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * @param chapter the chapter to query.
     */
    fun getValidChapterDirNames(chapter: Chapter): List<String> {
        return listOf(
            getChapterDirName(chapter, true),
            getChapterDirName(chapter, false),
            // Legacy chapter directory name used in v0.9.2 and before
            DiskUtil.buildValidFilename(chapter.name)
        )
    }
}
