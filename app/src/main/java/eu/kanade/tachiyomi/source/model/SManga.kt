package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SManga : Serializable {
    var url: String

    var title: String

    /**
     * Alternative titles for the manga (official translations, romanizations, etc.).
     *
     * @since tachiyomix 1.6
     */
    var altTitles: List<String>
        get() = emptyList()
        set(_) {}

    var artist: String?

    var author: String?

    /**
     * URL of the manga's banner image, typically a wide image shown in headers. May be null.
     *
     * @since tachiyomix 1.6
     */
    var banner: String?
        get() = null
        set(_) {}

    var description: String?

    var genre: String?

    /**
     * Manga genres in list form. Bridges to [genre] (comma separated) so existing storage and
     * sources that only set [genre] keep working.
     *
     * @since tachiyomix 1.6
     */
    var genres: List<String>
        get() = genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        set(value) { genre = value.joinToString(", ").ifBlank { null } }

    var status: Int

    /**
     * Age or content rating for the manga. Defaults to [ContentRating.SAFE].
     *
     * @since tachiyomix 1.6
     */
    var contentRating: ContentRating
        get() = ContentRating.SAFE
        set(_) {}

    /**
     * Source-provided rating score as a percentile (0-100), or null if unavailable.
     *
     * @since tachiyomix 1.6
     */
    var score: Int?
        get() = null
        set(_) {}

    /**
     * Preferred reading mode provided by the source, or null if the source mixes modes.
     *
     * @since tachiyomix 1.6
     */
    var readingMode: ReadingMode?
        get() = null
        set(_) {}

    var thumbnail_url: String?

    var update_strategy: UpdateStrategy

    var initialized: Boolean

    /**
     * Extra metadata associated with the manga. Not visible to users; intended for
     * internal or source-specific purposes (apps may use namespaced keys like "mihon.*").
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject
        get() = JsonObject(emptyMap())
        set(_) {}

    fun copyFrom(other: SManga) {
        // EXH -->
        if (other.title.isNotBlank()) {
            title = other.title
        }
        // EXH <--

        if (other.author != null) {
            author = other.author
        }

        if (other.artist != null) {
            artist = other.artist
        }

        if (other.description != null) {
            description = other.description
        }

        if (other.genre != null) {
            genre = other.genre
        }

        if (other.altTitles.isNotEmpty()) {
            altTitles = other.altTitles
        }

        if (other.banner != null) {
            banner = other.banner
        }

        if (other.thumbnail_url != null) {
            thumbnail_url = other.thumbnail_url
        }

        if (other.score != null) {
            score = other.score
        }

        if (other.readingMode != null) {
            readingMode = other.readingMode
        }

        contentRating = other.contentRating

        // Only copy memo when the source provides one so existing metadata isn't wiped on refresh.
        if (other.memo.isNotEmpty()) {
            memo = other.memo
        }

        status = other.status

        update_strategy = other.update_strategy

        if (!initialized) {
            initialized = other.initialized
        }
    }

    enum class ContentRating {
        SAFE,
        SUGGESTIVE,
        ADULT
    }

    enum class ReadingMode {
        RIGHT_TO_LEFT,
        LEFT_TO_RIGHT,
        LONG_STRIP
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        const val RECOMMENDS = 69 // nice

        fun create(): SManga {
            return SMangaImpl()
        }
    }
}
