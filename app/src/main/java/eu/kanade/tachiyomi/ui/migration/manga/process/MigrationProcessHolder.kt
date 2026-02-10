package eu.kanade.tachiyomi.ui.migration.manga.process

import android.view.View
import android.widget.PopupMenu
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.gson.Gson
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.databinding.MigrationMangaCardBinding
import eu.kanade.tachiyomi.databinding.MigrationProcessItemBinding
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.util.lang.launchUI
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.invisible
import eu.kanade.tachiyomi.util.view.setVectorCompat
import eu.kanade.tachiyomi.util.view.visible
import exh.MERGED_SOURCE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import java.text.DecimalFormat

class MigrationProcessHolder(
    private val view: View,
    private val adapter: MigrationProcessAdapter
) : BaseFlexibleViewHolder(view, adapter) {
    private val db: DatabaseHelper by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private var item: MigrationProcessItem? = null
    private val gson: Gson by injectLazy()

    private val binding = MigrationProcessItemBinding.bind(view)

    init {
        // We need to post a Runnable to show the popup to make sure that the PopupMenu is
        // correctly positioned. The reason being that the view may change position before the
        // PopupMenu is shown.
        binding.migrationMenu.setOnClickListener { it.post { showPopupMenu(it) } }
        binding.skipManga.setOnClickListener { it.post { adapter.removeManga(bindingAdapterPosition) } }
    }

    fun bind(item: MigrationProcessItem) {
        this.item = item
        launchUI {
            val manga = item.manga.manga()
            val source = item.manga.mangaSource()

            binding.migrationMenu.setVectorCompat(
                R.drawable.ic_more_vert_24dp,
                view.context
                    .getResourceColor(com.google.android.material.R.attr.colorOnPrimary)
            )
            binding.skipManga.setVectorCompat(
                R.drawable.ic_close_24dp,
                view.context.getResourceColor(
                    com.google.android.material.R
                        .attr.colorOnPrimary
                )
            )
            binding.migrationMenu.invisible()
            binding.skipManga.visible()
            binding.migrationMangaCardTo.resetManga()
            if (manga != null) {
                withContext(Dispatchers.Main) {
                    binding.migrationMangaCardFrom.attachManga(manga, source)
                    binding.migrationMangaCardFrom.root.setOnClickListener {
                        adapter.controller.router.pushController(
                            MangaController(
                                manga,
                                true
                            ).withFadeTransaction()
                        )
                    }
                }

                /*launchUI {
                    item.manga.progress.asFlow().collect { (max, progress) ->
                        withContext(Dispatchers.Main) {
                            migration_manga_card_to.search_progress.let { progressBar ->
                                progressBar.max = max
                                progressBar.progress = progress
                            }
                        }
                    }
                }*/

                val searchResult =
                    item.manga.searchResult.get()?.let {
                        db.getManga(it).executeAsBlocking()
                    }
                val resultSource =
                    searchResult?.source?.let {
                        sourceManager.get(it)
                    }
                withContext(Dispatchers.Main) {
                    if (item.manga.mangaId != this@MigrationProcessHolder.item?.manga?.mangaId ||
                        item.manga.migrationStatus == MigrationStatus.RUNNUNG
                    ) {
                        return@withContext
                    }
                    if (searchResult != null && resultSource != null) {
                        binding.migrationMangaCardTo.attachManga(searchResult, resultSource)
                        binding.migrationMangaCardTo.root.setOnClickListener {
                            adapter.controller.router.pushController(
                                MangaController(
                                    searchResult,
                                    true
                                ).withFadeTransaction()
                            )
                        }
                    } else {
                        binding.migrationMangaCardTo.loadingGroup.gone()
                        binding.migrationMangaCardTo.title.text =
                            view.context.applicationContext
                                .getString(R.string.no_alternatives_found)
                    }
                    binding.migrationMenu.visible()
                    binding.skipManga.gone()
                    adapter.sourceFinished()
                }
            }
        }
    }

    private fun MigrationMangaCardBinding.resetManga() {
        loadingGroup.visible()
        thumbnail.setImageDrawable(null)
        title.text = ""
        mangaSourceLabel.text = ""
        mangaChapters.text = ""
        mangaChapters.gone()
        mangaLastChapterLabel.text = ""
        root.setOnClickListener(null)
    }

    private fun MigrationMangaCardBinding.attachManga(
        manga: Manga,
        source: Source
    ) {
        loadingGroup.gone()
        GlideApp.with(view.context.applicationContext)
            .load(manga.toMangaThumbnail())
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .centerCrop()
            .dontAnimate()
            .into(thumbnail)

        title.text =
            manga.title.ifBlank {
                view.context.getString(R.string.unknown)
            }

        gradient.visible()
        mangaSourceLabel.text =
            if (source.id == MERGED_SOURCE_ID) {
                MergedSource.MangaConfig.readFromUrl(gson, manga.url).children.map {
                    sourceManager.getOrStub(it.source).toString()
                }.distinct().joinToString()
            } else {
                source.toString()
            }

        val mangaChaptersDB = db.getChapters(manga).executeAsBlocking()
        mangaChapters.visible()
        mangaChapters.text = mangaChaptersDB.size.toString()
        val latestChapter = mangaChaptersDB.maxByOrNull { it.chapter_number }?.chapter_number ?: -1f

        if (latestChapter > 0f) {
            mangaLastChapterLabel.text =
                root.context.getString(
                    R.string.latest_,
                    DecimalFormat("#.#").format(latestChapter)
                )
        } else {
            mangaLastChapterLabel.text =
                root.context.getString(
                    R.string.latest_,
                    root.context.getString(R.string.unknown)
                )
        }
    }

    private fun showPopupMenu(view: View) {
        val item = adapter.getItem(bindingAdapterPosition) ?: return

        // Create a PopupMenu, giving it the clicked view for an anchor
        val popup = PopupMenu(view.context, view)

        // Inflate our menu resource into the PopupMenu's Menu
        popup.menuInflater.inflate(R.menu.migration_single, popup.menu)

        val mangas = item.manga

        popup.menu.findItem(R.id.action_search_manually).isVisible = true
        // Hide download and show delete if the chapter is downloaded
        if (mangas.searchResult.content != null) {
            popup.menu.findItem(R.id.action_migrate_now).isVisible = true
            popup.menu.findItem(R.id.action_copy_now).isVisible = true
        }

        // Set a listener so we are notified if a menu item is clicked
        popup.setOnMenuItemClickListener { menuItem ->
            adapter.menuItemListener.onMenuItemClick(bindingAdapterPosition, menuItem)
            true
        }

        // Finally show the PopupMenu
        popup.show()
    }
}
