package eu.kanade.tachiyomi.ui.source.globalsearch

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.visible
import eu.kanade.tachiyomi.databinding.GlobalSearchControllerCardBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.util.system.LocaleHelper

/**
 * Holder that binds the [GlobalSearchItem] containing catalogue cards.
 *
 * @param view view of [GlobalSearchItem]
 * @param adapter instance of [GlobalSearchAdapter]
 */
class GlobalSearchHolder(view: View, val adapter: GlobalSearchAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = GlobalSearchControllerCardBinding.bind(view)

    /**
     * Adapter containing manga from search results.
     */
    private val mangaAdapter = GlobalSearchCardAdapter(adapter.controller)

    private var lastBoundResults: List<GlobalSearchCardItem>? = null

    init {
        // Set layout horizontal.
        binding.recycler.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
        binding.recycler.adapter = mangaAdapter

        binding.more.setOnClickListener {
            val item = adapter.getItem(bindingAdapterPosition)
            if (item != null) {
                adapter.moreClickListener.onMoreClick(item.source)
            }
        }
    }

    /**
     * Show the loading of source search result.
     *
     * @param item item of card.
     */
    fun bind(item: GlobalSearchItem) {
        val source = item.source
        val results = item.results

        val titlePrefix = if (item.highlighted) "▶" else ""
        val langSuffix = if (source.lang.isNotEmpty()) " (${source.lang})" else ""

        // Set Title with country code if available.
        binding.title.text = titlePrefix + source.name + langSuffix

        when {
            results == null -> {
                binding.progress.visible()
                showHolder()
            }
            results.isEmpty() -> {
                binding.progress.gone()
                hideHolder()
            }
            else -> {
                binding.progress.gone()
                showHolder()
            }
        }
        if (results !== lastBoundResults) {
            mangaAdapter.updateDataSet(results)
            lastBoundResults = results
        }
    }

    /**
     * Called from the presenter when a manga is initialized.
     *
     * @param manga the initialized manga.
     */
    fun setImage(manga: Manga) {
        getHolder(manga)?.setImage(manga)
    }

    /**
     * Returns the view holder for the given manga.
     *
     * @param manga the manga to find.
     * @return the holder of the manga or null if it's not bound.
     */
    private fun getHolder(manga: Manga): GlobalSearchCardHolder? {
        mangaAdapter.allBoundViewHolders.forEach { holder ->
            val item = mangaAdapter.getItem(holder.bindingAdapterPosition)
            if (item != null && item.manga.id!! == manga.id!!) {
                return holder as GlobalSearchCardHolder
            }
        }

        return null
    }

    private fun showHolder() {
        binding.title.visible()
        binding.source_card.visible()
        binding.more.visible() // EXH
    }

    private fun hideHolder() {
        binding.title.gone()
        binding.source_card.gone()
        binding.more.gone() // EXH
    }
}
