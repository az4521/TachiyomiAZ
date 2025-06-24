package eu.kanade.tachiyomi.ui.source.globalsearch

import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import uy.kohesive.injekt.injectLazy

/**
 * Adapter that holds the manga items from search results.
 *
 * @param controller instance of [GlobalSearchController].
 */
class GlobalSearchCardAdapter(
    controller: GlobalSearchController,
) : FlexibleAdapter<GlobalSearchMangaItem>(null, controller, true) {
    /**
     * Listen for browse item clicks.
     */
    val mangaClickListener: OnMangaClickListener = controller
    private val preferences: PreferencesHelper by injectLazy()
    val showOutlines = preferences.outlineOnCovers().get()

    /**
     * Listener which should be called when user clicks browse.
     * Note: Should only be handled by [GlobalSearchController]
     */
    interface OnMangaClickListener {
        fun onMangaClick(manga: Manga)

        fun onMangaLongClick(
            position: Int,
            adapter: GlobalSearchCardAdapter,
        )
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<*>,
    ) {
        // Bind view activation with current selection
        try {
            super.onBindViewHolder(holder, position, payloads)
        } catch (_: Exception) {
        }
        // Bind the item
//        val item: GlobalSearchMangaItem? = getItem(position)
//        if (item != null) {
//            holder.itemView.isEnabled = item.isEnabled()
//            item.bindViewHolder(
//                this as FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
//                holder as GlobalSearchMangaHolder,
//                position,
//                payloads.toMutableList(),
//            )
//            // Avoid to show the double background in case header has transparency
//            // The visibility will be restored when header is reset in StickyHeaderHelper
// //            if (areHeadersSticky() && isHeader(item) && !isFastScroll && mStickyHeaderHelper.stickyPosition >= 0 && payloads.isEmpty()) {
// //                val headerPos = flexibleLayoutManager.findFirstVisibleItemPosition() - 1
// //                if (headerPos == position) {
// //                    holder.itemView.visibility = View.INVISIBLE
// //                }
// //            }
//        }
//        // Endless Scroll
//        onLoadMore(position)
//        // Scroll Animations
// //        animateView(holder, position)
    }
}
