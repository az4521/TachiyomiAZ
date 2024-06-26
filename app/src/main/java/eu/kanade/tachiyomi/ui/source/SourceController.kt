package eu.kanade.tachiyomi.ui.source

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SourceMainControllerBinding
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.RootController
import eu.kanade.tachiyomi.ui.base.controller.requestPermissionsSafe
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.setting.SettingsSourcesController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.source.latest.LatestUpdatesController
import exh.ui.smartsearch.SmartSearchController
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize
import reactivecircus.flowbinding.appcompat.QueryTextEvent
import reactivecircus.flowbinding.appcompat.queryTextEvents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This controller shows and manages the different catalogues enabled by the user.
 * This controller should only handle UI actions, IO actions should be done by [SourcePresenter]
 * [SourceAdapter.OnBrowseClickListener] call function data on browse item click.
 * [SourceAdapter.OnLatestClickListener] call function data on latest item click
 */
class SourceController(bundle: Bundle? = null) :
    NucleusController<SourceMainControllerBinding, SourcePresenter>(bundle),
    RootController,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    SourceAdapter.OnBrowseClickListener,
    SourceAdapter.OnLatestClickListener {
    private val preferences: PreferencesHelper = Injekt.get()

    /**
     * Adapter containing sources.
     */
    private var adapter: SourceAdapter? = null

    private val smartSearchConfig: SmartSearchConfig? = args.getParcelable(SMART_SEARCH_CONFIG)

    // EXH -->
    private val mode = if (smartSearchConfig == null) Mode.CATALOGUE else Mode.SMART_SEARCH
    // EXH <--

    /**
     * Called when controller is initialized.
     */
    init {
        // Enable the option menu
        setHasOptionsMenu(mode == Mode.CATALOGUE)
    }

    override fun getTitle(): String? {
        return when (mode) {
            Mode.CATALOGUE -> applicationContext?.getString(R.string.label_sources)
            Mode.SMART_SEARCH -> "Find in another source"
        }
    }

    override fun createPresenter(): SourcePresenter {
        return SourcePresenter(controllerMode = mode)
    }

    /**
     * Initiate the view with [R.layout.source_main_controller].
     *
     * @param inflater used to load the layout xml.
     * @param container containing parent views.
     * @return inflated view.
     */
    override fun inflateView(
        inflater: LayoutInflater,
        container: ViewGroup
    ): View {
        binding = SourceMainControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        adapter = SourceAdapter(this)

        // Create recycler and set adapter.
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.adapter = adapter
        binding.recycler.addItemDecoration(SourceDividerItemDecoration(view.context))
        adapter?.fastScroller = binding.fastScroller

        requestPermissionsSafe(arrayOf(WRITE_EXTERNAL_STORAGE), 301)
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    override fun onChangeStarted(
        handler: ControllerChangeHandler,
        type: ControllerChangeType
    ) {
        super.onChangeStarted(handler, type)
        if (!type.isPush && handler is SettingsSourcesFadeChangeHandler) {
            presenter.updateSources()
        }
    }

    /**
     * Called when item is clicked
     */
    override fun onItemClick(
        view: View?,
        position: Int
    ): Boolean {
        val item = adapter?.getItem(position) as? SourceItem ?: return false
        val source = item.source
        when (mode) {
            Mode.CATALOGUE -> {
                // Open the catalogue view.
                openCatalogue(source, BrowseSourceController(source))
            }
            Mode.SMART_SEARCH ->
                router.pushController(
                    SmartSearchController(
                        Bundle().apply {
                            putLong(SmartSearchController.ARG_SOURCE_ID, source.id)
                            putParcelable(SmartSearchController.ARG_SMART_SEARCH_CONFIG, smartSearchConfig)
                        }
                    ).withFadeTransaction()
                )
        }
        return false
    }

    override fun onItemLongClick(position: Int) {
        val activity = activity ?: return
        val item = adapter?.getItem(position) as? SourceItem ?: return

        val isPinned = item.header?.code?.equals(SourcePresenter.PINNED_KEY) ?: false

        MaterialDialog(activity)
            .title(text = item.source.name)
            .listItems(
                items =
                listOf(
                    activity.getString(R.string.action_hide),
                    activity.getString(if (isPinned) R.string.action_unpin else R.string.action_pin)
                ),
                waitForPositiveButton = false
            ) { dialog, which, _ ->
                when (which) {
                    0 -> hideCatalogue(item.source)
                    1 -> pinCatalogue(item.source, isPinned)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun hideCatalogue(source: Source) {
        val current = preferences.hiddenCatalogues().get()
        preferences.hiddenCatalogues().set(current + source.id.toString())

        presenter.updateSources()
    }

    private fun pinCatalogue(
        source: Source,
        isPinned: Boolean
    ) {
        val current = preferences.pinnedCatalogues().get()
        if (isPinned) {
            preferences.pinnedCatalogues().set(current - source.id.toString())
        } else {
            preferences.pinnedCatalogues().set(current + source.id.toString())
        }

        presenter.updateSources()
    }

    /**
     * Called when browse is clicked in [SourceAdapter]
     */
    override fun onBrowseClick(position: Int) {
        onItemClick(null, position)
    }

    /**
     * Called when latest is clicked in [SourceAdapter]
     */
    override fun onLatestClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        openCatalogue(item.source, LatestUpdatesController(item.source))
    }

    /**
     * Opens a catalogue with the given controller.
     */
    private fun openCatalogue(
        source: CatalogueSource,
        controller: BrowseSourceController
    ) {
        preferences.lastUsedCatalogueSource().set(source.id)
        router.pushController(controller.withFadeTransaction())
    }

    /**
     * Adds items to the options menu.
     *
     * @param menu menu containing options.
     * @param inflater used to load the menu xml.
     */
    override fun onCreateOptionsMenu(
        menu: Menu,
        inflater: MenuInflater
    ) {
        // Inflate menu
        inflater.inflate(R.menu.source_main, menu)

        if (mode == Mode.SMART_SEARCH) {
            menu.findItem(R.id.action_search).isVisible = false
            menu.findItem(R.id.action_settings).isVisible = false
        }

        // Initialize search option.
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE

        // Change hint to show global search.
        searchView.queryHint = applicationContext?.getString(R.string.action_global_search_hint)

        // Create query listener which opens the global search view.
        searchView.queryTextEvents()
            .filterIsInstance<QueryTextEvent.QuerySubmitted>()
            .onEach { performGlobalSearch(it.queryText.toString()) }
            .launchIn(scope)
    }

    private fun performGlobalSearch(query: String) {
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    /**
     * Called when an option menu item has been selected by the user.
     *
     * @param item The selected item.
     * @return True if this event has been consumed, false if it has not.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Initialize option to open catalogue settings.
            R.id.action_settings -> {
                router.pushController(
                    (RouterTransaction.with(SettingsSourcesController()))
                        .popChangeHandler(SettingsSourcesFadeChangeHandler())
                        .pushChangeHandler(FadeChangeHandler())
                )
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Called to update adapter containing sources.
     */
    fun setSources(sources: List<IFlexible<*>>) {
        adapter?.updateDataSet(sources)
    }

    /**
     * Called to set the last used catalogue at the top of the view.
     */
    fun setLastUsedSource(item: SourceItem?) {
        adapter?.removeAllScrollableHeaders()
        if (item != null) {
            adapter?.addScrollableHeader(item)
            adapter?.addScrollableHeader(LangItem(SourcePresenter.LAST_USED_KEY))
        }
    }

    class SettingsSourcesFadeChangeHandler : FadeChangeHandler()

    @Parcelize
    data class SmartSearchConfig(val origTitle: String, val origMangaId: Long? = null) : Parcelable

    enum class Mode {
        CATALOGUE,
        SMART_SEARCH
    }

    companion object {
        const val SMART_SEARCH_CONFIG = "SMART_SEARCH_CONFIG"
    }
}
