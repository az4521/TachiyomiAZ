package eu.kanade.tachiyomi.ui.migration.manga.process

import android.view.MenuItem
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.migration.migrateMangaData
import eu.kanade.tachiyomi.util.system.launchUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy

class MigrationProcessAdapter(
    val controller: MigrationListController
) : FlexibleAdapter<MigrationProcessItem>(null, controller, true) {
    private val db: DatabaseHelper by injectLazy()
    var items: List<MigrationProcessItem> = emptyList()
    val preferences: PreferencesHelper by injectLazy()

    val menuItemListener: MigrationProcessInterface = controller

    override fun updateDataSet(items: List<MigrationProcessItem>?) {
        this.items = items ?: emptyList()
        super.updateDataSet(items)
    }

    interface MigrationProcessInterface {
        fun onMenuItemClick(
            position: Int,
            item: MenuItem
        )

        fun enableButtons()

        fun removeManga(item: MigrationProcessItem)

        fun noMigration()

        fun updateCount()
    }

    fun sourceFinished() {
        menuItemListener.updateCount()
        if (itemCount == 0) menuItemListener.noMigration()
        if (allMangasDone()) menuItemListener.enableButtons()
    }

    fun allMangasDone() =
        (
            items.all {
                it.manga.migrationStatus !=
                    MigrationStatus
                        .RUNNUNG
            } && items.any { it.manga.migrationStatus == MigrationStatus.MANGA_FOUND }
            )

    fun mangasSkipped() = (items.count { it.manga.migrationStatus == MigrationStatus.MANGA_NOT_FOUND })

    suspend fun performMigrations(copy: Boolean) {
        withContext(Dispatchers.IO) {
            // searchResult.get() and manga() suspend, so resolve them before opening the
            // transaction. That also stops a DB transaction being held open across
            // suspension points, which the previous version did.
            val pending =
                currentItems.mapNotNull { migratingManga ->
                    val manga = migratingManga.manga
                    if (!manga.searchResult.initialized) return@mapNotNull null
                    val targetId = manga.searchResult.get() ?: return@mapNotNull null
                    val toMangaObj = db.getManga(targetId) ?: return@mapNotNull null
                    val fromManga = manga.manga() ?: return@mapNotNull null
                    fromManga to toMangaObj
                }

            db.inTransaction {
                pending.forEach { (fromManga, toMangaObj) ->
                    migrateMangaInternal(fromManga, toMangaObj, !copy)
                }
            }
        }
    }

    fun migrateManga(
        position: Int,
        copy: Boolean
    ) {
        launchUI {
            val manga = getItem(position)?.manga ?: return@launchUI
            val targetId = manga.searchResult.get() ?: return@launchUI
            val toMangaObj = db.getManga(targetId) ?: return@launchUI
            val fromManga = manga.manga() ?: return@launchUI
            db.inTransaction {
                migrateMangaInternal(fromManga, toMangaObj, !copy)
            }
            removeManga(position)
        }
    }

    fun removeManga(position: Int) {
        val item = getItem(position) ?: return
        menuItemListener.removeManga(item)
        item.manga.migrationJob.cancel()
        removeItem(position)
        items = currentItems
        sourceFinished()
    }

    private fun migrateMangaInternal(
        prevManga: Manga,
        manga: Manga,
        replace: Boolean
    ) {
        if (controller.config == null) return

        // The rule itself is shared so both platforms carry the same data across a migration.
        migrateMangaData(
            db = db,
            prevManga = prevManga,
            manga = manga,
            flags = preferences.migrateFlags().get(),
            replace = replace
        )
    }
}
