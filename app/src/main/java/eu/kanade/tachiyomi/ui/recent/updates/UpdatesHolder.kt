package eu.kanade.tachiyomi.ui.recent.updates

import android.view.View
import android.widget.PopupMenu
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.databinding.UpdatesItemBinding
import eu.kanade.tachiyomi.util.system.getResourceColor

/**
 * Holder that contains chapter item
 * Uses R.layout.item_recent_chapters.
 * UI related actions should be called from here.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @param listener a listener to react to single tap and long tap events.
 * @constructor creates a new recent chapter holder.
 */
class UpdatesHolder(private val view: View, private val adapter: UpdatesAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = UpdatesItemBinding.bind(view)

    private var readColor = view.context.getResourceColor(R.attr.colorOnSurface, 0.38f)
    private var unreadColor = view.context.getResourceColor(R.attr.colorOnSurface)

    /**
     * Currently bound item.
     */
    private var item: UpdatesItem? = null

    init {
        // We need to post a Runnable to show the popup to make sure that the PopupMenu is
        // correctly positioned. The reason being that the view may change position before the
        // PopupMenu is shown.
        binding.chapter_menu.setOnClickListener { it.post { showPopupMenu(it) } }
        binding.manga_cover.setOnClickListener {
            adapter.coverClickListener.onCoverClick(bindingAdapterPosition)
        }
    }

    /**
     * Set values of view
     *
     * @param item item containing chapter information
     */
    fun bind(item: UpdatesItem) {
        this.item = item

        // Set chapter title
        binding.chapterTitle.text = item.chapter.name

        // Set manga title
        binding.mangaTitle.text = item.manga.title

        // Set cover
        GlideApp.with(itemView.context).clear(manga_cover)
        GlideApp.with(itemView.context)
            .load(item.manga.toMangaThumbnail())
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .circleCrop()
            .into(manga_cover)

        // Check if chapter is read and set correct color
        if (item.chapter.read) {
            binding.chapterTitle.setTextColor(readColor)
            binding.mangaTitle.setTextColor(readColor)
        } else {
            binding.chapterTitle.setTextColor(unreadColor)
            binding.mangaTitle.setTextColor(unreadColor)
        }

        // Set chapter status
        notifyStatus(item.status)
    }

    /**
     * Updates chapter status in view.
     *
     * @param status download status
     */
    fun notifyStatus(status: Int) = with(binding.downloadText) {
        when (status) {
            Download.QUEUE -> setText(R.string.chapter_queued)
            Download.DOWNLOADING -> setText(R.string.chapter_downloading)
            Download.DOWNLOADED -> setText(R.string.chapter_downloaded)
            Download.ERROR -> setText(R.string.chapter_error)
            else -> text = ""
        }
    }

    /**
     * Show pop up menu
     *
     * @param view view containing popup menu.
     */
    private fun showPopupMenu(view: View) = item?.let { item ->
        // Create a PopupMenu, giving it the clicked view for an anchor
        val popup = PopupMenu(view.context, view)

        // Inflate our menu resource into the PopupMenu's Menu
        popup.menuInflater.inflate(R.menu.chapter_recent, popup.menu)

        // Hide download and show delete if the chapter is downloaded and
        if (item.isDownloaded) {
            popup.menu.findItem(R.id.action_download).isVisible = false
            popup.menu.findItem(R.id.action_delete).isVisible = true
        }

        // Hide mark as unread when the chapter is unread
        if (!item.chapter.read /*&& mangaChapter.chapter.last_page_read == 0*/) {
            popup.menu.findItem(R.id.action_mark_as_unread).isVisible = false
        }

        // Hide mark as read when the chapter is read
        if (item.chapter.read) {
            popup.menu.findItem(R.id.action_mark_as_read).isVisible = false
        }

        // Set a listener so we are notified if a menu item is clicked
        popup.setOnMenuItemClickListener { menuItem ->
            with(adapter.controller) {
                when (menuItem.itemId) {
                    R.id.action_download -> downloadChapter(item)
                    R.id.action_delete -> deleteChapter(item)
                    R.id.action_mark_as_read -> markAsRead(listOf(item))
                    R.id.action_mark_as_unread -> markAsUnread(listOf(item))
                }
            }

            true
        }

        // Finally show the PopupMenu
        popup.show()
    }
}
