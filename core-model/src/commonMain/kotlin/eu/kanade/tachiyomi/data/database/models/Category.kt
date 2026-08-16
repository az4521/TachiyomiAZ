package eu.kanade.tachiyomi.data.database.models

import eu.kanade.tachiyomi.source.model.JavaSerializable

interface Category : JavaSerializable {
    var id: Int?

    var name: String

    var order: Int

    var flags: Int

    var mangaOrder: List<Long>

    companion object {
        fun create(name: String): Category =
            CategoryImpl().apply {
                this.name = name
            }

        fun createDefault(): Category = create("Default").apply { id = 0 }
    }
}
