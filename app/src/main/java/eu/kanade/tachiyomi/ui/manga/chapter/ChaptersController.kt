package eu.kanade.tachiyomi.ui.manga.chapter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluelinelabs.conductor.RouterTransaction
import com.elvishew.xlog.XLog
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.ChaptersControllerBinding
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.AnimeSource
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.video.VideoActivity
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.getCoordinates
import eu.kanade.tachiyomi.util.view.snack
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.view.clicks
import reactivecircus.flowbinding.swiperefreshlayout.refreshes
import rx.android.schedulers.AndroidSchedulers
import timber.log.Timber
import uy.kohesive.injekt.injectLazy

class ChaptersController :
    NucleusController<ChaptersControllerBinding, ChaptersPresenter>(),
    ActionMode.Callback,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    ChaptersAdapter.OnMenuItemClickListener,
    DownloadCustomChaptersDialog.Listener,
    DeleteChaptersDialog.Listener {
    private val sourceManager: SourceManager by injectLazy()

    /**
     * Adapter containing a list of chapters.
     */
    private var adapter: ChaptersAdapter? = null

    /**
     * Action mode for multiple selection.
     */
    private var actionMode: ActionMode? = null

    /**
     * Selected items. Used to restore selections after a rotation.
     */
    private val selectedItems = mutableSetOf<ChapterItem>()

    private var lastClickPosition = -1

    init {
        setHasOptionsMenu(true)
        setOptionsMenuHidden(true)
    }

    override fun createPresenter(): ChaptersPresenter {
        val ctrl = parentController as MangaController
        return ChaptersPresenter(
            ctrl.manga!!,
            ctrl.source!!,
            ctrl.chapterCountRelay,
            ctrl.lastUpdateRelay,
            ctrl.mangaFavoriteRelay
        )
    }

    override fun inflateView(
        inflater: LayoutInflater,
        container: ViewGroup
    ): View {
        binding = ChaptersControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        val ctrl = parentController as MangaController
        if (ctrl.manga == null || ctrl.source == null) return

        // Init RecyclerView and adapter
        adapter = ChaptersAdapter(this, view.context)

        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.addItemDecoration(DividerItemDecoration(view.context, DividerItemDecoration.VERTICAL))
        binding.recycler.setHasFixedSize(true)
        adapter?.fastScroller = binding.fastScroller

        binding.swipeRefresh.refreshes()
            .onEach { fetchChaptersFromSource(manualFetch = true) }
            .launchIn(scope)

        binding.fab.clicks()
            .onEach {
                val item = presenter.getNextUnreadChapter()
                if (item != null) {
                    // Create animation listener
                    val revealAnimationListener: Animator.AnimatorListener =
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationStart(animation: Animator) {
                                openChapter(item.chapter, true)
                            }
                        }

                    // Get coordinates and start animation
                    val coordinates = binding.fab.getCoordinates()
                    if (!binding.revealView.showRevealEffect(coordinates.x, coordinates.y, revealAnimationListener)) {
                        openChapter(item.chapter)
                    }
                } else {
                    view.context.toast(R.string.no_next_chapter)
                }
            }
            .launchIn(scope)

        presenter.redirectUserRelay
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeUntilDestroy { redirect ->
                XLog.d(
                    "Redirecting to updated manga (manga.id: %s, manga.title: %s, update: %s)!",
                    redirect.manga.id,
                    redirect.manga.title,
                    redirect.update
                )
                // Replace self
                parentController?.router?.replaceTopController(RouterTransaction.with(MangaController(redirect)))
            }
    }

    override fun onDestroyView(view: View) {
        adapter = null
        actionMode = null
        super.onDestroyView(view)
    }

    override fun onActivityResumed(activity: Activity) {
        if (view == null) return

        // Check if animation view is visible
        if (binding.revealView.visibility == View.VISIBLE) {
            // Show the unreveal effect
            val coordinates = binding.fab.getCoordinates()
            binding.revealView.hideRevealEffect(coordinates.x, coordinates.y, 1920)
        }
        super.onActivityResumed(activity)
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        inflater: MenuInflater
    ) {
        inflater.inflate(R.menu.chapters, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        // Initialize menu items.
        val menuFilterRead = menu.findItem(R.id.action_filter_read) ?: return
        val menuFilterUnread = menu.findItem(R.id.action_filter_unread)
        val menuFilterDownloaded = menu.findItem(R.id.action_filter_downloaded)
        val menuFilterBookmarked = menu.findItem(R.id.action_filter_bookmarked)
        val menuFilterEmpty = menu.findItem(R.id.action_filter_empty)

        // Set correct checkbox values.
        menuFilterRead.isChecked = presenter.onlyRead()
        menuFilterUnread.isChecked = presenter.onlyUnread()
        menuFilterDownloaded.isChecked = presenter.onlyDownloaded()
        menuFilterDownloaded.isEnabled = !presenter.forceDownloaded()
        menuFilterBookmarked.isChecked = presenter.onlyBookmarked()

        val filterSet = presenter.onlyRead() || presenter.onlyUnread() || presenter.onlyDownloaded() || presenter.onlyBookmarked()

        if (filterSet) {
            val filterColor = activity!!.getResourceColor(R.attr.colorFilterActive)
            menu.findItem(R.id.action_filter).icon?.let { DrawableCompat.setTint(it, filterColor) }
        }

        // Only show remove filter option if there's a filter set.
        menuFilterEmpty.isVisible = filterSet

        // Disable unread filter option if read filter is enabled.
        if (presenter.onlyRead()) {
            menuFilterUnread.isEnabled = false
        }
        // Disable read filter option if unread filter is enabled.
        if (presenter.onlyUnread()) {
            menuFilterRead.isEnabled = false
        }

        // Display mode submenu
        if (presenter.manga.displayMode == Manga.DISPLAY_NAME) {
            menu.findItem(R.id.display_title).isChecked = true
        } else {
            menu.findItem(R.id.display_chapter_number).isChecked = true
        }

        // Sorting mode submenu
        val sortingItem =
            when (presenter.manga.sorting) {
                Manga.SORTING_SOURCE -> R.id.sort_by_source
                Manga.SORTING_NUMBER -> R.id.sort_by_number
                Manga.SORTING_UPLOAD_DATE -> R.id.sort_by_upload_date
                else -> throw NotImplementedError("Unimplemented sorting method")
            }
        menu.findItem(sortingItem).isChecked = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.display_title -> {
                item.isChecked = true
                setDisplayMode(Manga.DISPLAY_NAME)
            }
            R.id.display_chapter_number -> {
                item.isChecked = true
                setDisplayMode(Manga.DISPLAY_NUMBER)
            }

            R.id.sort_by_source -> {
                item.isChecked = true
                presenter.setSorting(Manga.SORTING_SOURCE)
            }
            R.id.sort_by_number -> {
                item.isChecked = true
                presenter.setSorting(Manga.SORTING_NUMBER)
            }
            R.id.sort_by_upload_date -> {
                item.isChecked = true
                presenter.setSorting(Manga.SORTING_UPLOAD_DATE)
            }

            R.id.download_next, R.id.download_next_5, R.id.download_next_10,
            R.id.download_custom, R.id.download_unread, R.id.download_all
            -> downloadChapters(item.itemId)

            R.id.action_filter_unread -> {
                item.isChecked = !item.isChecked
                presenter.setUnreadFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_read -> {
                item.isChecked = !item.isChecked
                presenter.setReadFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_downloaded -> {
                item.isChecked = !item.isChecked
                presenter.setDownloadedFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_bookmarked -> {
                item.isChecked = !item.isChecked
                presenter.setBookmarkedFilter(item.isChecked)
                activity?.invalidateOptionsMenu()
            }
            R.id.action_filter_empty -> {
                presenter.removeFilters()
                activity?.invalidateOptionsMenu()
            }
            R.id.action_sort -> presenter.revertSortOrder()
        }
        return super.onOptionsItemSelected(item)
    }

    fun onNextChapters(chapters: List<ChapterItem>) {
        // If the list is empty and it hasn't requested previously, fetch chapters from source
        // We use presenter chapters instead because they are always unfiltered
        if (!presenter.hasRequested && presenter.chapters.isEmpty()) {
            fetchChaptersFromSource()
        }

        val mangaController = parentController as MangaController
        if (mangaController.update ||
            // Auto-update old format galleries
            (
                (presenter.manga.source == EH_SOURCE_ID || presenter.manga.source == EXH_SOURCE_ID) &&
                    chapters.size == 1 && chapters.first().date_upload == 0L
                )
        ) {
            mangaController.update = false
            fetchChaptersFromSource()
        }

        val adapter = adapter ?: return
        adapter.updateDataSet(chapters)

        if (selectedItems.isNotEmpty()) {
            adapter.clearSelection() // we need to start from a clean state, index may have changed
            createActionModeIfNeeded()
            selectedItems.forEach { item ->
                val position = adapter.indexOf(item)
                if (position != -1 && !adapter.isSelected(position)) {
                    adapter.toggleSelection(position)
                }
            }
            actionMode?.invalidate()
        }
    }

    private fun fetchChaptersFromSource(manualFetch: Boolean = false) {
        binding.swipeRefresh.isRefreshing = true
        presenter.fetchChaptersFromSource(manualFetch)
    }

    fun onFetchChaptersDone() {
        binding.swipeRefresh.isRefreshing = false
    }

    fun onFetchChaptersError(error: Throwable) {
        binding.swipeRefresh.isRefreshing = false
        activity?.toast(error.message)
        // [EXH]
        XLog.w("> Failed to fetch chapters!", error)
        XLog.w(
            "> (source.id: %s, source.name: %s, manga.id: %s, manga.url: %s)",
            presenter.source.id,
            presenter.source.name,
            presenter.manga.id,
            presenter.manga.url
        )
    }

    fun onChapterStatusChange(download: Download) {
        getHolder(download.chapter)?.notifyStatus(download.status)
    }

    private fun getHolder(chapter: Chapter): ChapterHolder? {
        return binding.recycler.findViewHolderForItemId(chapter.id!!) as? ChapterHolder
    }

    fun openChapter(
        chapter: Chapter,
        hasAnimation: Boolean = false
    ) {
        val activity = activity ?: return

        val intent =
            if (sourceManager.getOrStub(presenter.manga.source) is AnimeSource) {
                VideoActivity.newIntent(activity, presenter.manga, chapter)
            } else {
                ReaderActivity.newIntent(activity, presenter.manga, chapter)
            }
        if (hasAnimation) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    override fun onItemClick(
        view: View,
        position: Int
    ): Boolean {
        val adapter = adapter ?: return false
        val item = adapter.getItem(position) ?: return false
        return if (actionMode != null && adapter.mode == SelectableAdapter.Mode.MULTI) {
            lastClickPosition = position
            toggleSelection(position)
            true
        } else {
            openChapter(item.chapter)
            false
        }
    }

    override fun onItemLongClick(position: Int) {
        createActionModeIfNeeded()
        when {
            lastClickPosition == -1 -> setSelection(position)
            lastClickPosition > position ->
                for (i in position until lastClickPosition)
                    setSelection(i)
            lastClickPosition < position ->
                for (i in lastClickPosition + 1..position)
                    setSelection(i)
            else -> setSelection(position)
        }
        lastClickPosition = position
        adapter?.notifyDataSetChanged()
    }

    // SELECTIONS & ACTION MODE

    private fun toggleSelection(position: Int) {
        val adapter = adapter ?: return
        val item = adapter.getItem(position) ?: return
        adapter.toggleSelection(position)
        adapter.notifyDataSetChanged()
        if (adapter.isSelected(position)) {
            selectedItems.add(item)
        } else {
            selectedItems.remove(item)
        }
        actionMode?.invalidate()
    }

    private fun setSelection(position: Int) {
        val adapter = adapter ?: return
        val item = adapter.getItem(position) ?: return
        if (!adapter.isSelected(position)) {
            adapter.toggleSelection(position)
            selectedItems.add(item)
            actionMode?.invalidate()
        }
    }

    private fun getSelectedChapters(): List<ChapterItem> {
        val adapter = adapter ?: return emptyList()
        return adapter.selectedPositions.mapNotNull { adapter.getItem(it) }
    }

    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this)
        }
    }

    private fun destroyActionModeIfNeeded() {
        lastClickPosition = -1
        actionMode?.finish()
    }

    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu
    ): Boolean {
        mode.menuInflater.inflate(R.menu.chapter_selection, menu)
        adapter?.mode = SelectableAdapter.Mode.MULTI
        return true
    }

    @SuppressLint("StringFormatInvalid")
    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu
    ): Boolean {
        val count = adapter?.selectedItemCount ?: 0
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            destroyActionModeIfNeeded()
        } else {
            mode.title = count.toString()

            val chapters = getSelectedChapters()
            menu.findItem(R.id.action_download)?.isVisible = chapters.any { !it.isDownloaded }
            menu.findItem(R.id.action_delete)?.isVisible = chapters.any { it.isDownloaded }
            menu.findItem(R.id.action_bookmark)?.isVisible = chapters.any { !it.chapter.bookmark }
            menu.findItem(R.id.action_remove_bookmark)?.isVisible = chapters.all { it.chapter.bookmark }
            menu.findItem(R.id.action_mark_as_read)?.isVisible = chapters.any { !it.chapter.read }
            menu.findItem(R.id.action_mark_as_unread)?.isVisible = chapters.all { it.chapter.read }
            menu.findItem(R.id.action_remove_page_cache)?.isVisible = true

            // Hide FAB to avoid interfering with the bottom action toolbar
            binding.fab.hide()
        }
        return false
    }

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem
    ): Boolean {
        return onActionItemClicked(item)
    }

    private fun onActionItemClicked(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> selectAll()
            R.id.action_select_inverse -> selectInverse()
            R.id.action_download -> downloadChapters(getSelectedChapters())
            R.id.action_delete -> showDeleteChaptersConfirmationDialog()
            R.id.action_bookmark -> bookmarkChapters(getSelectedChapters(), true)
            R.id.action_remove_bookmark -> bookmarkChapters(getSelectedChapters(), false)
            R.id.action_mark_as_read -> markAsRead(getSelectedChapters())
            R.id.action_mark_as_unread -> markAsUnread(getSelectedChapters())
            R.id.action_mark_previous_as_read -> markPreviousAsRead(getSelectedChapters()[0])
            R.id.action_remove_page_cache -> removePageCache(getSelectedChapters())
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        adapter?.mode = SelectableAdapter.Mode.SINGLE
        adapter?.clearSelection()
        selectedItems.clear()
        actionMode = null
    }

    override fun onMenuItemClick(
        position: Int,
        item: MenuItem
    ) {
        val chapter = adapter?.getItem(position) ?: return
        val chapters = listOf(chapter)

        when (item.itemId) {
            R.id.action_download -> downloadChapters(chapters)
            R.id.action_delete -> deleteChapters(chapters)
            R.id.action_bookmark -> bookmarkChapters(chapters, true)
            R.id.action_remove_bookmark -> bookmarkChapters(chapters, false)
            R.id.action_mark_as_read -> markAsRead(chapters)
            R.id.action_mark_as_unread -> markAsUnread(chapters)
            R.id.action_mark_previous_as_read -> markPreviousAsRead(chapter)
            R.id.action_remove_page_cache -> removePageCache(chapters)
        }
    }

    // SELECTION MODE ACTIONS

    private fun selectAll() {
        val adapter = adapter ?: return
        adapter.selectAll()
        selectedItems.addAll(adapter.items)
        actionMode?.invalidate()
    }

    private fun selectInverse() {
        val adapter = adapter ?: return
        for (i in 0..adapter.itemCount) {
            adapter.toggleSelection(i)
        }
        actionMode?.invalidate()
        adapter.notifyDataSetChanged()
    }

    private fun markAsRead(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, true)
        destroyActionModeIfNeeded()
    }

    private fun markAsUnread(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, false)
        destroyActionModeIfNeeded()
    }

    private fun downloadChapters(chapters: List<ChapterItem>) {
        val view = view
        destroyActionModeIfNeeded()
        presenter.downloadChapters(chapters)
        if (view != null && !presenter.manga.favorite) {
            binding.recycler.snack(view.context.getString(R.string.snack_add_to_library), Snackbar.LENGTH_INDEFINITE) {
                setAction(R.string.action_add) {
                    presenter.addToLibrary()
                }
            }
        }
        destroyActionModeIfNeeded()
    }

    private fun showDeleteChaptersConfirmationDialog() {
        DeleteChaptersDialog(this).showDialog(router)
    }

    override fun deleteChapters() {
        deleteChapters(getSelectedChapters())
    }

    private fun markPreviousAsRead(chapter: ChapterItem) {
        val adapter = adapter ?: return
        val chapters = if (presenter.sortDescending()) adapter.items.reversed() else adapter.items
        val chapterPos = chapters.indexOf(chapter)
        if (chapterPos != -1) {
            markAsRead(chapters.take(chapterPos))
        }
        destroyActionModeIfNeeded()
    }

    private fun removePageCache(
        chapters: List<ChapterItem>
    ) {
        destroyActionModeIfNeeded()
        presenter.removePageCache(chapters)
        destroyActionModeIfNeeded()
    }

    private fun bookmarkChapters(
        chapters: List<ChapterItem>,
        bookmarked: Boolean
    ) {
        destroyActionModeIfNeeded()
        presenter.bookmarkChapters(chapters, bookmarked)
        destroyActionModeIfNeeded()
    }

    fun deleteChapters(chapters: List<ChapterItem>) {
        destroyActionModeIfNeeded()
        if (chapters.isEmpty()) return

        presenter.deleteChapters(chapters)
        destroyActionModeIfNeeded()
    }

    fun onChaptersDeleted(chapters: List<ChapterItem>) {
        // this is needed so the downloaded text gets removed from the item
        chapters.forEach {
            adapter?.updateItem(it)
        }
        adapter?.notifyDataSetChanged()
    }

    fun onChaptersDeletedError(error: Throwable) {
        Timber.e(error)
    }

    // OVERFLOW MENU DIALOGS

    private fun setDisplayMode(id: Int) {
        presenter.setDisplayMode(id)
        adapter?.notifyDataSetChanged()
    }

    private fun getUnreadChaptersSorted() =
        presenter.chapters
            .filter { !it.read && it.status == Download.NOT_DOWNLOADED }
            .distinctBy { it.name }
            .sortedByDescending { it.source_order }

    private fun downloadChapters(choice: Int) {
        val chaptersToDownload =
            when (choice) {
                R.id.download_next -> getUnreadChaptersSorted().take(1)
                R.id.download_next_5 -> getUnreadChaptersSorted().take(5)
                R.id.download_next_10 -> getUnreadChaptersSorted().take(10)
                R.id.download_custom -> {
                    showCustomDownloadDialog()
                    return
                }
                R.id.download_unread -> presenter.chapters.filter { !it.read }
                R.id.download_all -> presenter.chapters
                else -> emptyList()
            }
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
        destroyActionModeIfNeeded()
    }

    private fun showCustomDownloadDialog() {
        DownloadCustomChaptersDialog(this, presenter.chapters.size).showDialog(router)
    }

    override fun downloadCustomChapters(amount: Int) {
        val chaptersToDownload = getUnreadChaptersSorted().take(amount)
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }
}
