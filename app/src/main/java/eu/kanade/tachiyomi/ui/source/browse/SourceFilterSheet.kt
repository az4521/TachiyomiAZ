package eu.kanade.tachiyomi.ui.source.browse

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.view.inflate
import eu.kanade.tachiyomi.widget.SimpleNavigationView
import exh.savedsearches.EXHSavedSearch

class SourceFilterSheet
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    SimpleNavigationView(context, attrs) {
    val adapter: FlexibleAdapter<IFlexible<*>> =
        FlexibleAdapter<IFlexible<*>>(null)
            .setDisplayHeadersAtStartUp(true)
    // .setStickyHeaders(true)

    var onSearchClicked = {}

    var onResetClicked = {}

    // EXH -->
    var onSaveClicked = {}
    // EXH <--

    // EXH -->
    var onSavedSearchClicked: (Int) -> Unit = {}
    // EXH <--

    // EXH -->
    var onSavedSearchDeleteClicked: (Int, String) -> Unit = { index, name -> }
    // EXH <--

    private val saveSearchBtn: ImageButton
    private val searchBtn: Button
    private val resetBtn: Button
    private val savedSearches: LinearLayout

    init {
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        val view = inflate(R.layout.source_filter_sheet)
        ((view as ViewGroup).findViewById(R.id.source_filter_content) as ViewGroup).addView(recycler)
        addView(view)

        saveSearchBtn = view.findViewById(R.id.save_search_btn)
        searchBtn = view.findViewById(R.id.search_btn)
        resetBtn = view.findViewById(R.id.reset_btn)
        savedSearches = view.findViewById(R.id.saved_searches)

        saveSearchBtn.setOnClickListener { onSaveClicked() }
        searchBtn.setOnClickListener { onSearchClicked() }
        resetBtn.setOnClickListener { onResetClicked() }
    }

    fun setFilters(items: List<IFlexible<*>>) {
        adapter.updateDataSet(items)
    }

    // EXH -->
    fun setSavedSearches(searches: List<EXHSavedSearch>) {
        savedSearches.removeAllViews()

        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        saveSearchBtn.visibility = if (searches.size < MAX_SAVED_SEARCHES) View.VISIBLE else View.GONE

        searches.withIndex().sortedBy { it.value.name }.forEach { (index, search) ->
            val restoreBtn = TextView(context)
            restoreBtn.text = search.name
            val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            params.gravity = Gravity.CENTER
            restoreBtn.layoutParams = params
            restoreBtn.gravity = Gravity.CENTER
            restoreBtn.setBackgroundResource(outValue.resourceId)
            restoreBtn.setPadding(8.dpToPx, 8.dpToPx, 8.dpToPx, 8.dpToPx)
            restoreBtn.setOnClickListener { onSavedSearchClicked(index) }
            restoreBtn.setOnLongClickListener {
                onSavedSearchDeleteClicked(index, search.name)
                true
            }
            savedSearches.addView(restoreBtn)
        }
    }

    companion object {
        const val MAX_SAVED_SEARCHES = 500 // if you want more than this, fuck you, i guess
    }
    // EXH <--
}
