package eu.kanade.tachiyomi.ui.recents

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterItem
import eu.kanade.tachiyomi.ui.recents.RecentsViewType.GroupedAll

class RecentMangaItem(
    val mch: MangaChapterHistory = MangaChapterHistory.createBlank(),
    chapter: Chapter = ChapterImpl(),
    header: AbstractHeaderItem<*>?,
) : BaseChapterItem<BaseChapterHolder, AbstractHeaderItem<*>>(chapter, header) {
    var downloadInfo = listOf<DownloadInfo>()

    override fun getLayoutRes(): Int =
        if (mch.manga.id == null) {
            R.layout.recents_footer_item
        } else {
            R.layout.recent_manga_item
        }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): BaseChapterHolder =
        if (mch.manga.id == null) {
            RecentMangaFooterHolder(view, adapter as RecentMangaAdapter)
        } else {
            RecentMangaHolder(view, adapter as RecentMangaAdapter)
        }

    override fun isSwipeable(): Boolean = mch.manga.id != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is RecentMangaItem) {
            return if (mch.manga.id == null) {
                (header as? RecentMangaHeaderItem)?.recentsType ==
                    (other.header as? RecentMangaHeaderItem)?.recentsType
            } else {
                chapter.id == other.chapter.id
            }
        }
        return false
    }

    override fun hashCode(): Int =
        if (mch.manga.id == null) {
            -((header as? RecentMangaHeaderItem)?.recentsType ?: 0).hashCode()
        } else {
            (chapter.id ?: 0L).hashCode()
        }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: BaseChapterHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        if (mch.manga.id == null && holder is RecentMangaFooterHolder) {
            holder.bind((header as? RecentMangaHeaderItem)?.recentsType ?: 0)
        } else if (chapter.id != null && holder is RecentMangaHolder) {
            holder.bind(this)
            val recentMangaAdapter = adapter as RecentMangaAdapter
            val useContainers = recentMangaAdapter.viewType.isUpdates || recentMangaAdapter.viewType == GroupedAll
            if (useContainers) {
                val setTop =
                    (recentMangaAdapter.getItem(position - 1) as? RecentMangaItem)
                        ?.mch
                        ?.manga
                        ?.id == null
                val setBottom =
                    (recentMangaAdapter.getItem(position + 1) as? RecentMangaItem)
                        ?.mch
                        ?.manga
                        ?.id == null
                holder.setCorners(setTop, setBottom)
            } else {
                holder.useContainers(false)
            }
        }
    }

    class DownloadInfo {
        private var _status: Download.State = Download.State.default

        var chapterId: Long? = 0L

        val progress: Int
            get() {
                val pages = download?.pages ?: return 0
                return pages.map(Page::progress).average().toInt()
            }

        var status: Download.State
            get() = download?.status ?: _status
            set(value) {
                _status = value
            }

        @Transient var download: Download? = null

        val isDownloaded: Boolean
            get() = status == Download.State.DOWNLOADED
    }
}
