package eu.kanade.tachiyomi.data.database.models

class CategoryImpl : Category {
    override var id: Int? = null

    override lateinit var name: String

    override var order: Int = 0

    override var flags: Int = 0

    override var mangaOrder: List<Long> = emptyList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        // this::class rather than javaClass: the same exact-class check, but available
        // off the JVM. `is` would not do -- LibraryManga extends MangaImpl, so a subclass would
        // start comparing equal to its parent.
        if (other == null || this::class != other::class) return false

        val category = other as Category
        return name == category.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}
