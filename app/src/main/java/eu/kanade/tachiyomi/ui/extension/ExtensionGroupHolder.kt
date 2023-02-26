package eu.kanade.tachiyomi.ui.extension

import android.annotation.SuppressLint
import android.view.View
import androidx.core.view.isVisible
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.databinding.ExtensionCardHeaderBinding

class ExtensionGroupHolder(view: View, adapter: FlexibleAdapter<*>) :
    FlexibleViewHolder(view, adapter) {

    private val binding = ExtensionCardHeaderBinding.bind(view)

    @SuppressLint("SetTextI18n")
    fun bind(item: ExtensionGroupItem) {
        var text = item.name
        if (item.showSize) {
            text += " (${item.size})"
        }

        binding.title.text = text

        binding.actionButton.isVisible = item.actionLabel != null && item.actionOnClick != null
        binding.actionButton.text = item.actionLabel
        binding.actionButton.setOnClickListener(if (item.actionLabel != null) item.actionOnClick else null)
    }
}
