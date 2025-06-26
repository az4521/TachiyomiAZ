package eu.kanade.tachiyomi.ui.manga.chapter

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.ui.manga.MangaDetailsAdapter

class ChapterItem(
    chapter: Chapter,
    val manga: Manga,
) : BaseChapterItem<ChapterHolder, AbstractHeaderItem<FlexibleViewHolder>>(chapter) {
    var isLocked = false

    override fun getLayoutRes(): Int = R.layout.chapters_item

    override fun isSelectable(): Boolean = true

    override fun isSwipeable(): Boolean = !isLocked

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): ChapterHolder = ChapterHolder(view, adapter as MangaDetailsAdapter)

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: ChapterHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(this, manga)
        val mangaAdapter = adapter as MangaDetailsAdapter
        val setTop = mangaAdapter.getItem(position - 1) !is ChapterItem
        val setBottom = mangaAdapter.getItem(position + 1) !is ChapterItem
        holder.setCorners(setTop, setBottom)
    }

    override fun unbindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>?,
        holder: ChapterHolder?,
        position: Int,
    ) {
        super.unbindViewHolder(adapter, holder, position)
        (adapter as MangaDetailsAdapter).controller.dismissPopup(position)
    }
}
