package eu.kanade.tachiyomi.ui.download

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.ItemTouchHelper
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadItemBinding
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.view.setVectorCompat

/**
 * Class used to hold the data of a download.
 * All the elements from the layout file "download_item" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @constructor creates a new download holder.
 */
class DownloadHolder(
    private val view: View,
    val adapter: DownloadAdapter,
) : BaseFlexibleViewHolder(view, adapter) {
    private val binding = DownloadItemBinding.bind(view)
    private var waveAnimation: ValueAnimator? = null

    init {
        setDragHandleView(binding.reorder)
        binding.downloadMenu.setOnClickListener { it.post { showPopupMenu(it) } }
    }

    private lateinit var download: Download

    /**
     * Binds this holder with the given category.
     *
     * @param download The download to bind.
     */
    fun bind(download: Download) {
        this.download = download
        // Update the chapter name.
        binding.chapterTitle.text =
            download.chapter
                .preferredChapterName(itemView.context, download.manga, adapter.preferences)

        // Update the manga title
        binding.title.text = download.manga.title

        // Update the progress bar and the number of downloaded pages
        val pages = download.pages
        if (pages == null) {
            binding.downloadProgress.isIndeterminate = true
            binding.downloadProgress.setWavelength(0)
            binding.downloadProgress.progress = 0
            binding.downloadProgress.max = 1
            binding.downloadProgressText.text = ""
        } else {
            binding.downloadProgress.max = pages.size * 100
            notifyProgress(false)
            setWave(DownloadJob.isRunning(binding.downloadProgress.context), false)
            notifyDownloadedPages()
        }

        binding.downloadMenu.setVectorCompat(
            R.drawable.ic_more_vert_24dp,
            R.attr.colorOnBackground,
        )
    }

    /**
     * Updates the progress bar of the download.
     */
    fun notifyProgress(animate: Boolean = true) {
        val pages = download.pages ?: return
        if (binding.downloadProgress.max == 1) {
            binding.downloadProgress.max = pages.size * 100
        }
        if (binding.downloadProgress.isIndeterminate) {
            binding.downloadProgress.isIndeterminate = false
            setWave(DownloadJob.isRunning(binding.downloadProgress.context), animate)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.downloadProgress.setProgress(download.pageProgress, animate)
        } else {
            binding.downloadProgress.progress = download.pageProgress
        }
    }

    fun setWave(
        isRunning: Boolean,
        animate: Boolean = true,
    ) {
        binding.downloadProgress.setWavelength(
            if (!binding.downloadProgress.isIndeterminate) {
                30.dpToPx
            } else {
                0
            },
        )
        if (!animate) {
            binding.downloadProgress.waveAmplitude = if (!isRunning) 0 else 2.dpToPx
            return
        }
        waveAnimation?.cancel()
        waveAnimation = ValueAnimator.ofInt(0, 2.dpToPx)
        waveAnimation?.addUpdateListener { valueAnimator ->
            binding.downloadProgress.waveAmplitude = valueAnimator.animatedValue as Int
        }
        waveAnimation?.duration = 100
        if (!isRunning) {
            waveAnimation?.reverse()
        } else {
            waveAnimation?.start()
        }
    }

    /**
     * Updates the text field of the number of downloaded pages.
     */
    fun notifyDownloadedPages() {
        val pages = download.pages ?: return
        binding.downloadProgressText.text = "${download.downloadedImages}/${pages.size}"
    }

    override fun onActionStateChanged(
        position: Int,
        actionState: Int,
    ) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.root.isDragged = true
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.downloadItemListener.onItemReleased(position)
        binding.root.isDragged = false
        binding.root.cardElevation = 0f
    }

    private fun showPopupMenu(view: View) {
        adapter.getItem(flexibleAdapterPosition) ?: return

        // Create a PopupMenu, giving it the clicked view for an anchor
        val popup = PopupMenu(view.context, view)

        // Inflate our menu resource into the PopupMenu's Menu
        popup.menuInflater.inflate(R.menu.download_single, popup.menu)

        popup.menu.findItem(R.id.move_to_top).isVisible = flexibleAdapterPosition != 0
        popup.menu.findItem(R.id.move_to_bottom).isVisible = flexibleAdapterPosition != adapter
            .itemCount - 1

        // Set a listener so we are notified if a menu item is clicked
        popup.setOnMenuItemClickListener { menuItem ->
            adapter.downloadItemListener.onMenuItemClick(flexibleAdapterPosition, menuItem)
            true
        }

        // Finally show the PopupMenu
        popup.show()
    }

    override fun getFrontView(): View = binding.frontView

    override fun getRearStartView(): View = binding.startView

    override fun getRearEndView(): View = binding.endView
}
