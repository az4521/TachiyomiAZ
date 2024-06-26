package eu.kanade.tachiyomi.ui.source.filter

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractExpandableHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.flexibleadapter.items.ISectionable
import eu.davidea.viewholders.ExpandableViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.util.view.setVectorCompat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GroupItem(val filter: Filter.Group<*>) : AbstractExpandableHeaderItem<GroupItem.Holder, ISectionable<*, *>>() {
    init {
        // --> EH
        isExpanded = Injekt.get<PreferencesHelper>().eh_expandFilters().get()
        // <-- EH
    }

    override fun getLayoutRes(): Int {
        return R.layout.navigation_view_group
    }

    override fun getItemViewType(): Int {
        return 101
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<androidx.recyclerview.widget.RecyclerView.ViewHolder>>
    ): Holder {
        return Holder(view, adapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<androidx.recyclerview.widget.RecyclerView.ViewHolder>>,
        holder: Holder,
        position: Int,
        payloads: List<Any?>?
    ) {
        holder.title.text = filter.name

        holder.icon.setVectorCompat(
            if (isExpanded) {
                R.drawable.ic_expand_more_white_24dp
            } else {
                R.drawable.ic_chevron_right_white_24dp
            }
        )

        holder.itemView.setOnClickListener(holder)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return filter == (other as GroupItem).filter
    }

    override fun hashCode(): Int {
        return filter.hashCode()
    }

    open class Holder(view: View, adapter: FlexibleAdapter<*>) : ExpandableViewHolder(view, adapter, true) {
        val title: TextView = itemView.findViewById(R.id.title)
        val icon: ImageView = itemView.findViewById(R.id.expand_icon)

        override fun shouldNotifyParentOnClick(): Boolean {
            return true
        }
    }
}
