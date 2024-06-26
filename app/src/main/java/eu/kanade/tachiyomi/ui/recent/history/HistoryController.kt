package eu.kanade.tachiyomi.ui.recent.history

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupRestoreService
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.databinding.HistoryControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.NoToolbarElevationController
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.RootController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.source.browse.ProgressItem
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.appcompat.queryTextChanges

/**
 * Fragment that shows recently read manga.
 * Uses [R.layout.history_controller].
 * UI related actions should be called from here.
 */
class HistoryController :
    NucleusController<HistoryControllerBinding, HistoryPresenter>(),
    RootController,
    NoToolbarElevationController,
    FlexibleAdapter.OnUpdateListener,
    FlexibleAdapter.EndlessScrollListener,
    HistoryAdapter.OnRemoveClickListener,
    HistoryAdapter.OnResumeClickListener,
    HistoryAdapter.OnItemClickListener,
    RemoveHistoryDialog.Listener {
    init {
        setHasOptionsMenu(true)
    }

    /**
     * Adapter containing the recent manga.
     */
    var adapter: HistoryAdapter? = null
        private set

    /**
     * Endless loading item.
     */
    private var progressItem: ProgressItem? = null
    private var query = ""

    override fun getTitle(): String? {
        return resources?.getString(R.string.label_recent_manga)
    }

    override fun createPresenter(): HistoryPresenter {
        return HistoryPresenter()
    }

    override fun inflateView(
        inflater: LayoutInflater,
        container: ViewGroup
    ): View {
        binding = HistoryControllerBinding.inflate(inflater)
        return binding.root
    }

    /**
     * Called when view is created
     *
     * @param view created view
     */
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        // Initialize adapter
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        adapter = HistoryAdapter(this@HistoryController)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.adapter = adapter
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    /**
     * Populate adapter with chapters
     *
     * @param mangaHistory list of manga history
     */
    fun onNextManga(
        mangaHistory: List<HistoryItem>,
        cleanBatch: Boolean = false
    ) {
        if (adapter?.itemCount ?: 0 == 0 || cleanBatch) {
            resetProgressItem()
        }
        if (cleanBatch) {
            adapter?.updateDataSet(mangaHistory)
        } else {
            adapter?.onLoadMoreComplete(mangaHistory)
        }
    }

    fun onAddPageError(error: Throwable) {
        adapter?.onLoadMoreComplete(null)
        adapter?.endlessTargetCount = 1
    }

    override fun onUpdateEmptyView(size: Int) {
        if (size > 0) {
            binding.emptyView.hide()
        } else {
            binding.emptyView.show(R.string.information_no_recent_manga)
        }
    }

    /**
     * Sets a new progress item and reenables the scroll listener.
     */
    private fun resetProgressItem() {
        progressItem = ProgressItem()
        adapter?.endlessTargetCount = 0
        adapter?.setEndlessScrollListener(this, progressItem!!)
    }

    override fun onLoadMore(
        lastPosition: Int,
        currentPage: Int
    ) {
        val view = view ?: return
        if (BackupRestoreService.isRunning(view.context.applicationContext)) {
            onAddPageError(Throwable())
            return
        }
        val adapter = adapter ?: return
        presenter.requestNext(adapter.itemCount, query)
    }

    override fun noMoreLoad(newItemsSize: Int) {}

    override fun onResumeClick(position: Int) {
        val activity = activity ?: return
        val (manga, chapter, _) = (adapter?.getItem(position) as? HistoryItem)?.mch ?: return

        val nextChapter = presenter.getNextChapter(chapter, manga)
        if (nextChapter != null) {
            val intent = ReaderActivity.newIntent(activity, manga, nextChapter)
            startActivity(intent)
        } else {
            activity.toast(R.string.no_next_chapter)
        }
    }

    override fun onRemoveClick(position: Int) {
        val (manga, _, history) = (adapter?.getItem(position) as? HistoryItem)?.mch ?: return
        RemoveHistoryDialog(this, manga, history).showDialog(router)
    }

    override fun onItemClick(position: Int) {
        val manga = (adapter?.getItem(position) as? HistoryItem)?.mch?.manga ?: return
        router.pushController(MangaController(manga).withFadeTransaction())
    }

    override fun removeHistory(
        manga: Manga,
        history: History,
        all: Boolean
    ) {
        if (all) {
            // Reset last read of chapter to 0L
            presenter.removeAllFromHistory(manga.id!!)
            /*val safeAdapter = adapter ?: return
            val items = (0 until safeAdapter.itemCount).filter {
                val item = safeAdapter.getItem(it)
                if (item is RecentlyReadItem)
                    item.mch.manga.id == manga.id
                else
                    false
            }
            adapter?.removeItems(items)*/
        } else {
            // Remove all chapters belonging to manga from library
            presenter.removeFromHistory(history)
        }
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        inflater: MenuInflater
    ) {
        inflater.inflate(R.menu.recently_read, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()
        }
        searchView.queryTextChanges()
            .filter { router.backstack.lastOrNull()?.controller() == this }
            .onEach {
                query = it.toString()
                presenter.updateList(query)
            }
            .launchIn(scope)

        // Fixes problem with the overflow icon showing up in lieu of search
        searchItem.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    activity?.invalidateOptionsMenu()
                    return true
                }
            }
        )
    }
}
