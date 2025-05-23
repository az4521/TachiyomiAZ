package eu.kanade.tachiyomi.ui.source

import android.os.Bundle
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import rx.Observable
import rx.Subscription
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.TreeMap

/**
 * Presenter of [SourceController]
 * Function calls should be done from here. UI calls should be done from the controller.
 *
 * @param sourceManager manages the different sources.
 * @param preferences application preferences.
 */
class SourcePresenter(
    val sourceManager: SourceManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val controllerMode: SourceController.Mode
) : BasePresenter<SourceController>() {
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    var sources = getEnabledSources()

    /**
     * Subscription for retrieving enabled sources.
     */
    private var sourceSubscription: Subscription? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        // Load enabled and last used sources
        updateSources()
    }

    /**
     * Unsubscribe and create a new subscription to fetch enabled sources.
     */
    private fun loadSources() {
        sourceSubscription?.unsubscribe()

        val pinnedSources = mutableListOf<SourceItem>()
        val pinnedCatalogues = preferences.pinnedCatalogues().get()

        val map =
            TreeMap<String, MutableList<CatalogueSource>> { d1, d2 ->
                // Catalogues without a lang defined will be placed at the end
                when {
                    d1 == "" && d2 != "" -> 1
                    d2 == "" && d1 != "" -> -1
                    else -> d1.compareTo(d2)
                }
            }
        val byLang = sources.groupByTo(map) { it.lang }
        var sourceItems =
            byLang.flatMap {
                val langItem = LangItem(it.key)
                it.value.map { source ->
                    if (source.id.toString() in pinnedCatalogues) {
                        pinnedSources.add(SourceItem(source, LangItem(PINNED_KEY), controllerMode == SourceController.Mode.CATALOGUE))
                    }

                    SourceItem(source, langItem, controllerMode == SourceController.Mode.CATALOGUE)
                }
            }

        if (pinnedSources.isNotEmpty()) {
            sourceItems = pinnedSources + sourceItems
        }

        sourceSubscription =
            Observable.just(sourceItems)
                .subscribeLatestCache(SourceController::setSources)
    }

    private fun loadLastUsedSource() {
        // Immediate initial load
        preferences.lastUsedCatalogueSource().get().let { updateLastUsedSource(it) }

        // Subsequent updates
        preferences.lastUsedCatalogueSource().asFlow()
            .drop(1)
            .distinctUntilChanged()
            .onEach { updateLastUsedSource(it) }
            .launchIn(scope)
    }

    private fun updateLastUsedSource(sourceId: Long) {
        if (preferences.hideLastUsedSource().get()) {
            view().subscribe { view -> view?.setLastUsedSource(null) }
        } else {
            val source =
                (sourceManager.get(sourceId) as? CatalogueSource)?.let {
                    SourceItem(it, showButtons = controllerMode == SourceController.Mode.CATALOGUE)
                }
            source?.let {
                view().subscribe { view -> view?.setLastUsedSource(it) }
            }
        }
    }

    fun updateSources() {
        sources = getEnabledSources()
        loadSources()
        loadLastUsedSource()
    }

    /**
     * Returns a list of enabled sources ordered by language and name.
     *
     * @return list containing enabled sources.
     */
    private fun getEnabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hiddenCatalogues = preferences.hiddenCatalogues().get()

        return sourceManager.getVisibleCatalogueSources()
            .filter { it.lang in languages }
            .filterNot { it.id.toString() in hiddenCatalogues }
            .sortedBy { "(${it.lang}) ${it.name}" } +
            sourceManager.get(LocalSource.ID) as LocalSource
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}
