package eu.kanade.tachiyomi.ui.recents

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.marginStart
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.CornerFamily
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterHistory
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.image.coil.loadManga
import eu.kanade.tachiyomi.databinding.RecentMangaItemBinding
import eu.kanade.tachiyomi.databinding.RecentSubChapterItemBinding
import eu.kanade.tachiyomi.ui.download.DownloadButton
import eu.kanade.tachiyomi.ui.manga.chapter.BaseChapterHolder
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.system.cardColor
import eu.kanade.tachiyomi.util.system.contextCompatColor
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.timeSpanFromNow
import eu.kanade.tachiyomi.util.view.setAnimVectorCompat
import eu.kanade.tachiyomi.util.view.setCards
import java.util.Date
import java.util.concurrent.TimeUnit

class RecentMangaHolder(
    view: View,
    val adapter: RecentMangaAdapter,
) : BaseChapterHolder(view, adapter) {
    private val binding = RecentMangaItemBinding.bind(view)
    var chapterId: Long? = null

    private val isUpdates get() = adapter.viewType.isUpdates
    private val isSmallUpdates get() = isUpdates && !adapter.showUpdatedTime

    init {
        binding.recentCard.setCardBackgroundColor(itemView.context.cardColor)
        binding.cardLayout.setOnClickListener { adapter.delegate.onCoverClick(flexibleAdapterPosition) }
        binding.removeHistory.setOnClickListener { adapter.delegate.onRemoveHistoryClicked(flexibleAdapterPosition) }
        binding.showMoreChapters.setOnClickListener { _ ->
            val moreVisible = !binding.moreChaptersLayout.isVisible
            binding.moreChaptersLayout.isVisible = moreVisible
            adapter.delegate.updateExpandedExtraChapters(flexibleAdapterPosition, moreVisible)
            binding.showMoreChapters.setAnimVectorCompat(
                if (moreVisible) {
                    R.drawable.anim_expand_more_to_less
                } else {
                    R.drawable.anim_expand_less_to_more
                },
            )
            if (moreVisible) {
                binding.moreChaptersLayout.children.forEach { view ->
                    RecentSubChapterItemBinding.bind(view).updateDivider()
                }
            }
            if (isUpdates &&
                binding.moreChaptersLayout.children.any { view ->
                    !RecentSubChapterItemBinding
                        .bind(view)
                        .subtitle.text
                        .isNullOrBlank()
                }
            ) {
                showScanlatorInBody(moreVisible)
            } else {
                addMoreUpdatesText(!moreVisible)
            }
            if (adapter.viewType.isHistory) {
                readLastText(!moreVisible)
                    .takeIf { it.isNotEmpty() }
                    ?.let { binding.body.text = it }
            }
            binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                height = binding.recentCard.height
            }
            val transition =
                TransitionSet()
                    .addTransition(androidx.transition.ChangeBounds())
                    .addTransition(androidx.transition.Slide())
            transition.duration =
                itemView.resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            TransitionManager.beginDelayedTransition(adapter.recyclerView, transition)
        }
        updateCards()
        binding.frontView.layoutTransition?.enableTransitionType(LayoutTransition.APPEARING)
    }

    fun updateCards() {
        setCards(adapter.showOutline, binding.card, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(item: RecentMangaItem) {
        val showDLs = adapter.showDownloads
        binding.recentCard.transitionName = "recents chapter $bindingAdapterPosition transition"
        val showRemoveHistory = adapter.showRemoveHistory
        val showTitleFirst = adapter.showTitleFirst
        binding.downloadButton.downloadButton.isVisible = when (showDLs) {
            RecentMangaAdapter.ShowRecentsDLs.None -> false
            RecentMangaAdapter.ShowRecentsDLs.OnlyUnread, RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded -> !item.chapter.read
            RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded -> true
            RecentMangaAdapter.ShowRecentsDLs.All -> true
        } &&
            !item.mch.manga.isLocal()

        binding.cardLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = (if (isSmallUpdates) 40 else 80).dpToPx
            width = (if (isSmallUpdates) 40 else 60).dpToPx
        }
        listOf(binding.title, binding.subtitle).forEach {
            it.updateLayoutParams<ConstraintLayout.LayoutParams> {
                if (isSmallUpdates) {
                    if (it == binding.title) topMargin = 5.dpToPx
                    endToStart = R.id.button_layout
                    endToEnd = -1
                } else {
                    if (it == binding.title) topMargin = 2.dpToPx
                    endToStart = -1
                    endToEnd = R.id.main_view
                }
            }
        }
        binding.buttonLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (isSmallUpdates) {
                topToBottom = -1
                topToTop = R.id.card_layout
                bottomToBottom = R.id.card_layout
                topMargin = 4.dpToPx
            } else {
                topToTop = -1
                topToBottom = R.id.subtitle
                bottomToBottom = R.id.main_view
                topMargin = 0
            }
        }
        val freeformCovers = !isSmallUpdates && !adapter.uniformCovers
        with(binding.coverThumbnail) {
            adjustViewBounds = freeformCovers
            scaleType = if (!freeformCovers) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        listOf(binding.coverThumbnail, binding.card).forEach {
            it.updateLayoutParams<ViewGroup.LayoutParams> {
                width =
                    if (!freeformCovers) {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
            }
        }

        binding.removeHistory.isVisible = item.mch.history.id != null && showRemoveHistory
        val context = itemView.context
        val chapterName =
            item.chapter.preferredChapterName(context, item.mch.manga, adapter.preferences)

        listOf(binding.title, binding.subtitle).forEach {
            it.apply {
                setCompoundDrawablesRelative(null, null, null, null)
                translationX = 0f
                text =
                    if (!showTitleFirst.xor(it === binding.subtitle)) {
                        ChapterUtil.setTextViewForChapter(this, item)
                        chapterName
                    } else {
                        setTextColor(ChapterUtil.readColor(context, item))
                        item.mch.manga.title
                    }
            }
        }
        if (binding.frontView.translationX == 0f) {
            binding.read.setImageResource(
                if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
            )
        }

        binding.showMoreChapters.isVisible = item.mch.extraChapters.isNotEmpty() &&
            !adapter.delegate.alwaysExpanded()
        binding.moreChaptersLayout.isVisible = item.mch.extraChapters.isNotEmpty() &&
            adapter.delegate.areExtraChaptersExpanded(flexibleAdapterPosition)
        val moreVisible = binding.moreChaptersLayout.isVisible

        binding.body.isVisible = !isSmallUpdates
        binding.body.text =
            when {
                item.mch.chapter.id == null -> context.timeSpanFromNow(R.string.added_, item.mch.manga.date_added)
                isSmallUpdates -> ""
                item.mch.history.id == null -> {
                    if (isUpdates) {
                        if (adapter.sortByFetched) {
                            context.timeSpanFromNow(R.string.fetched_, item.chapter.date_fetch)
                        } else {
                            context.timeSpanFromNow(R.string.updated_, item.chapter.date_upload)
                        }
                    } else {
                        context.timeSpanFromNow(R.string.fetched_, item.chapter.date_fetch) + "\n" +
                            context.timeSpanFromNow(R.string.updated_, item.chapter.date_upload)
                    }
                }
                item.chapter.id != item.mch.chapter.id -> readLastText(!moreVisible)
                item.chapter.pages_left > 0 && !item.chapter.read ->
                    context.timeSpanFromNow(R.string.read_, item.mch.history.last_read) +
                        "\n" +
                        itemView.resources.getQuantityString(
                            R.plurals.pages_left,
                            item.chapter.pages_left,
                            item.chapter.pages_left,
                        )
                else -> context.timeSpanFromNow(R.string.read_, item.mch.history.last_read)
            }
        if ((context as? Activity)?.isDestroyed != true) {
            binding.coverThumbnail.loadManga(item.mch.manga)
        }
        if (!item.mch.manga.isLocal()) {
            notifyStatus(
                if (adapter.isSelected(flexibleAdapterPosition)) Download.State.CHECKED else item.status,
                item.progress,
                item.chapter.read,
            )
        }

        binding.showMoreChapters.setImageResource(
            if (moreVisible) {
                R.drawable.ic_expand_less_24dp
            } else {
                R.drawable.ic_expand_more_24dp
            },
        )
        val extraIds =
            binding.moreChaptersLayout.children
                .toList()
                .shorterList()
                .map {
                    it?.findViewById<DownloadButton>(R.id.download_button)?.tag
                }.toList()
        if (extraIds ==
            item.mch.extraChapters
                .shorterList()
                .map { it?.id }
        ) {
            var hasSameChapter = false
            item.mch.extraChapters.shorterList().forEachIndexed { index, chapter ->
                val binding =
                    RecentSubChapterItemBinding.bind(binding.moreChaptersLayout.getChildAt(index))
                binding.configureView(chapter, item)
                if (isUpdates && !binding.subtitle.text.isNullOrBlank() && !hasSameChapter) {
                    showScanlatorInBody(moreVisible, item)
                    hasSameChapter = true
                }
            }
            addMoreUpdatesText(!moreVisible, item)
        } else {
            binding.moreChaptersLayout.removeAllViews()
            var hasSameChapter = false
            if (item.mch.extraChapters.isNotEmpty()) {
                item.mch.extraChapters.shorterList().forEach { chapter ->
                    val binding =
                        RecentSubChapterItemBinding.inflate(
                            LayoutInflater.from(context),
                            binding.moreChaptersLayout,
                            true,
                        )
                    binding.configureView(chapter, item)
                    if (isUpdates && !binding.subtitle.text.isNullOrBlank() && !hasSameChapter) {
                        showScanlatorInBody(moreVisible, item)
                        hasSameChapter = true
                    }
                }
                addMoreUpdatesText(!moreVisible, item)
            } else {
                chapterId = null
            }
        }
        listOf(binding.mainView, binding.downloadButton.root, binding.showMoreChapters, binding.cardLayout).forEach {
            it.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    binding.endView.translationY = binding.recentCard.y
                    binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = binding.recentCard.height
                    }
                    binding.read.setImageResource(
                        if (item.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
                    )
                    binding.endView.shapeAppearanceModel = binding.recentCard.shapeAppearanceModel
                    chapterId = null
                }
                false
            }
        }
    }

    private fun addMoreUpdatesText(
        add: Boolean,
        originalItem: RecentMangaItem? = null,
    ) {
        val item = originalItem ?: adapter.getItem(bindingAdapterPosition) as? RecentMangaItem ?: return
        val originalText = binding.body.text.toString()
        val andMoreText =
            itemView.context.resources.getQuantityString(
                R.plurals.notification_and_n_more,
                (item.mch.extraChapters.size),
                (item.mch.extraChapters.size),
            )
        if (add &&
            item.mch.extraChapters.isNotEmpty() &&
            isUpdates &&
            !isSmallUpdates &&
            !originalText.contains(andMoreText)
        ) {
            val text = "${originalText.substringBefore("\n")}\n$andMoreText"
            binding.body.text = text
        } else if (!add && originalText.contains(andMoreText)) {
            binding.body.text = originalText.removeSuffix("\n$andMoreText")
        }
    }

    private fun readLastText(
        show: Boolean,
        originalItem: RecentMangaItem? = null,
    ): String {
        val item = originalItem ?: adapter.getItem(bindingAdapterPosition) as? RecentMangaItem ?: return ""
        val notValidNum = item.mch.chapter.chapter_number <= 0
        return if (item.chapter.id != item.mch.chapter.id) {
            if (show) {
                itemView.context.timeSpanFromNow(R.string.read_, item.mch.history.last_read) + "\n"
            } else {
                ""
            } +
                itemView.context.getString(
                    if (notValidNum) R.string.last_read_ else R.string.last_read_chapter_,
                    if (notValidNum) item.mch.chapter.name else adapter.decimalFormat.format(item.mch.chapter.chapter_number),
                )
        } else {
            ""
        }
    }

    private fun showScanlatorInBody(
        add: Boolean,
        originalItem: RecentMangaItem? = null,
    ) {
        val item = originalItem ?: adapter.getItem(bindingAdapterPosition) as? RecentMangaItem ?: return
        val originalText = binding.body.text.toString()
        binding.body.maxLines = 2
        val scanlator = item.chapter.scanlator ?: return
        if (add) {
            if (isSmallUpdates) {
                binding.body.maxLines = 1
                binding.body.text = item.chapter.scanlator
                binding.body.isVisible = true
            } else if (!originalText.contains(scanlator)) {
                val text = "${originalText.substringBefore("\n")}\n$scanlator"
                binding.body.text = text
            }
        } else {
            if (isSmallUpdates) {
                binding.body.isVisible = false
            } else {
                binding.body.text = originalText.removeSuffix("\n$scanlator")
                addMoreUpdatesText(true, item)
            }
        }
    }

    private fun <T> List<T>.shorterList(): List<T?> = if (size > 21) take(10) + null + takeLast(10) else this

    @SuppressLint("ClickableViewAccessibility")
    private fun RecentSubChapterItemBinding.configureBlankView(count: Int) {
        val context = itemView.context
        title.text =
            context.resources.getQuantityString(R.plurals.notification_and_n_more, count, count)
        downloadButton.root.isVisible = false
        downloadButton.root.tag = null
        title.textSize = 13f
        title.setTextColor(context.contextCompatColor(R.color.read_chapter))
        textLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintMinHeight = 16.dpToPx
        }
        root.tag = "sub ${-1L}"
        root.setOnLongClickListener { false }
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                chapterId = -1L
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun RecentSubChapterItemBinding.configureView(
        chapter: ChapterHistory?,
        item: RecentMangaItem,
    ) {
        root.setCardBackgroundColor(root.context.cardColor)
        if (chapter?.id == null) {
            configureBlankView(item.mch.extraChapters.size - 20)
            return
        }
        textLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            matchConstraintMinHeight = 48.dpToPx
        }
        val context = itemView.context
        val showDLs = adapter.showDownloads
        title.text = chapter.preferredChapterName(context, item.mch.manga, adapter.preferences)
        ChapterUtil.setTextViewForChapter(title, chapter)
        val notReadYet = item.chapter.id != item.mch.chapter.id && item.mch.history.id != null
        subtitle.text = chapter.history?.let { history ->
            context
                .timeSpanFromNow(R.string.read_, history.last_read)
                .takeIf {
                    Date().time - history.last_read < TimeUnit.DAYS.toMillis(1) ||
                        notReadYet ||
                        adapter.dateFormat.run {
                            format(history.last_read) != format(item.mch.history.last_read)
                        }
                }
        } ?: ""
        if (isUpdates &&
            chapter.isRecognizedNumber &&
            chapter.chapter_number == item.chapter.chapter_number &&
            !chapter.scanlator.isNullOrBlank()
        ) {
            subtitle.text = chapter.scanlator
        }
        subtitle.isVisible = subtitle.text.isNotBlank()
        title.textSize = (if (subtitle.isVisible) 14f else 14.5f)
        root.setOnClickListener {
            adapter.delegate.onSubChapterClicked(
                bindingAdapterPosition,
                chapter,
                it,
            )
        }
        root.setOnLongClickListener {
            adapter.delegate.onItemLongClick(bindingAdapterPosition, chapter)
        }
        listOf(root, downloadButton.root).forEach {
            it.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    binding.read.setImageResource(
                        if (chapter.read) R.drawable.ic_eye_off_24dp else R.drawable.ic_eye_24dp,
                    )
                    binding.endView.translationY = binding.moreChaptersLayout.y + root.y
                    binding.endView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = root.height
                    }
                    binding.endView.shapeAppearanceModel = root.shapeAppearanceModel
                    chapterId = chapter.id
                }
                false
            }
        }
        textLayout.updatePaddingRelative(start = if (isSmallUpdates) 64.dpToPx else 84.dpToPx)
        updateDivider()
        root.transitionName = "recents sub chapter ${chapter.id ?: 0L} transition"
        root.tag = "sub ${chapter.id}"
        downloadButton.root.tag = chapter.id
        val downloadInfo =
            item.downloadInfo.find { it.chapterId == chapter.id } ?: return
        downloadButton.downloadButton.setOnClickListener {
            downloadOrRemoveMenu(it, chapter, downloadInfo.status)
        }
        downloadButton.downloadButton.isVisible = when (showDLs) {
            RecentMangaAdapter.ShowRecentsDLs.None -> false
            RecentMangaAdapter.ShowRecentsDLs.OnlyUnread, RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded -> !chapter.read
            RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded -> true
            RecentMangaAdapter.ShowRecentsDLs.All -> true
        } &&
            !item.mch.manga.isLocal()
        notifySubStatus(
            chapter,
            if (adapter.isSelected(flexibleAdapterPosition)) {
                Download.State.CHECKED
            } else {
                downloadInfo.status
            },
            downloadInfo.progress,
            chapter.read,
        )
    }

    private fun RecentSubChapterItemBinding.updateDivider() {
        divider.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = if (isSmallUpdates) 64.dpToPx else 84.dpToPx
        }
    }

    override fun onLongClick(view: View?): Boolean {
        super.onLongClick(view)
        val item = adapter.getItem(flexibleAdapterPosition) as? RecentMangaItem ?: return false
        return item.mch.history.id != null
    }

    fun notifyStatus(
        status: Download.State,
        progress: Int,
        isRead: Boolean,
        animated: Boolean = false,
    ) {
        binding.downloadButton.downloadButton.setDownloadStatus(status, progress, animated)
        val isChapterRead =
            if (adapter.showDownloads == RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded) isRead else true
        binding.downloadButton.downloadButton.isVisible =
            when (adapter.showDownloads) {
                RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded,
                RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded,
                ->
                    status !in Download.State.CHECKED..Download.State.NOT_DOWNLOADED || !isChapterRead
                else -> binding.downloadButton.downloadButton.isVisible
            }
    }

    fun notifySubStatus(
        chapter: Chapter,
        status: Download.State,
        progress: Int,
        isRead: Boolean,
        animated: Boolean = false,
    ) {
        val downloadButton = binding.moreChaptersLayout.findViewWithTag<DownloadButton>(chapter.id) ?: return
        downloadButton.setDownloadStatus(status, progress, animated)
        val isChapterRead =
            if (adapter.showDownloads == RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded) isRead else true
        downloadButton.isVisible =
            when (adapter.showDownloads) {
                RecentMangaAdapter.ShowRecentsDLs.UnreadOrDownloaded,
                RecentMangaAdapter.ShowRecentsDLs.OnlyDownloaded,
                ->
                    status !in Download.State.CHECKED..Download.State.NOT_DOWNLOADED || !isChapterRead
                else -> downloadButton.isVisible
            }
    }

    override fun getFrontView(): View =
        if (chapterId == null) {
            binding.recentCard
        } else {
            binding.moreChaptersLayout.children.find { it.tag == "sub $chapterId" }
                ?: binding.recentCard
        }

    override fun getRearEndView(): View? = if (chapterId == -1L) null else binding.endView

    fun isContained(): Boolean = binding.recentCard.marginStart != 0

    fun useContainers(enabled: Boolean) {
        val cardList = mutableListOf(binding.recentCard)
        cardList.add(binding.endView)
        cardList.addAll(binding.moreChaptersLayout.children.map { it as MaterialCardView })
        if (!enabled) {
            cardList.forEach {
                it.radius = 0f
            }
        }
        val margins = if (enabled) 8.dpToPx else 0
        val bgColor by lazy { itemView.context.getResourceColor(R.attr.background) }
        val cardColor by lazy { itemView.context.cardColor }
        cardList.forEach {
            it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = margins
                marginEnd = margins
            }
            if (it != binding.endView) {
                it.setCardBackgroundColor(if (enabled) cardColor else bgColor)
            }
        }
        binding.card.setCardBackgroundColor(if (enabled) cardColor else bgColor)
    }

    fun setCorners(
        top: Boolean,
        bottom: Boolean,
    ) {
        useContainers(true)
        val finalCard = binding.moreChaptersLayout.children.lastOrNull() as? MaterialCardView
        val hasSubChapters = finalCard != null
        val mainCornerRadius = itemView.resources.getDimension(R.dimen.container_main_corner)
        val subCornerRadius = itemView.resources.getDimension(R.dimen.container_sub_corner)
        val topRadius = if (top) mainCornerRadius else subCornerRadius
        val bottomRadius = if (bottom) mainCornerRadius else subCornerRadius
        val shapeModel =
            binding.recentCard.shapeAppearanceModel
                .toBuilder()
                .apply {
                    setTopLeftCorner(CornerFamily.ROUNDED, topRadius)
                    setTopRightCorner(CornerFamily.ROUNDED, topRadius)
                    setBottomLeftCorner(
                        CornerFamily.ROUNDED,
                        if (hasSubChapters) 0f else bottomRadius,
                    )
                    setBottomRightCorner(
                        CornerFamily.ROUNDED,
                        if (hasSubChapters) 0f else bottomRadius,
                    )
                }.build()
        binding.recentCard.shapeAppearanceModel = shapeModel
        binding.endView.shapeAppearanceModel = shapeModel
        finalCard?.shapeAppearanceModel =
            binding.recentCard.shapeAppearanceModel
                .toBuilder()
                .apply {
                    setTopLeftCorner(CornerFamily.ROUNDED, 0f)
                    setTopRightCorner(CornerFamily.ROUNDED, 0f)
                    setBottomLeftCorner(CornerFamily.ROUNDED, bottomRadius)
                    setBottomRightCorner(CornerFamily.ROUNDED, bottomRadius)
                }.build()
    }
}
