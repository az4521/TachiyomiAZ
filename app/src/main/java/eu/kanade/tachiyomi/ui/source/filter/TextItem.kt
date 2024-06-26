package eu.kanade.tachiyomi.ui.source.filter

import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputLayout
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.widget.textChanges

open class TextItem(val filter: Filter.Text) : AbstractFlexibleItem<TextItem.Holder>() {
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    override fun getLayoutRes(): Int {
        return R.layout.navigation_view_text
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>
    ): Holder {
        return Holder(view, adapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: Holder,
        position: Int,
        payloads: List<Any?>?
    ) {
        holder.wrapper.hint = filter.name
        holder.edit.setText(filter.state)
        holder.edit.textChanges()
            .onEach { filter.state = it.toString() }
            .launchIn(scope)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return filter == (other as TextItem).filter
    }

    override fun hashCode(): Int {
        return filter.hashCode()
    }

    class Holder(view: View, adapter: FlexibleAdapter<*>) : FlexibleViewHolder(view, adapter) {
        val wrapper: TextInputLayout = itemView.findViewById(R.id.nav_view_item_wrapper)
        val edit: EditText = itemView.findViewById(R.id.nav_view_item)
    }
}
