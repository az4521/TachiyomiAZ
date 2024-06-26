package eu.kanade.tachiyomi.ui.source.latest

import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourcePresenter

/**
 * Controller that shows the latest manga from the catalogue. Inherit [BrowseSourceController].
 */
class LatestUpdatesController(bundle: Bundle) : BrowseSourceController(bundle) {
    constructor(source: CatalogueSource) : this(
        Bundle().apply {
            putLong(SOURCE_ID_KEY, source.id)
        }
    )

    override fun createPresenter(): BrowseSourcePresenter {
        return LatestUpdatesPresenter(args.getLong(SOURCE_ID_KEY))
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_search).isVisible = false
        menu.findItem(R.id.action_set_filter).isVisible = false
    }

    override fun createSecondaryDrawer(drawer: androidx.drawerlayout.widget.DrawerLayout): ViewGroup? {
        return null
    }

    override fun cleanupSecondaryDrawer(drawer: androidx.drawerlayout.widget.DrawerLayout) {
    }
}
