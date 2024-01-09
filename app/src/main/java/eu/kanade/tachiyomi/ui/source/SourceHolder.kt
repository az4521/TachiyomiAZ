package eu.kanade.tachiyomi.ui.source

import android.view.View
import androidx.core.view.isVisible
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.SourceMainControllerCardItemBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.base.holder.SlicedHolder
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.visible
import io.github.mthli.slice.Slice
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.setVectorCompat

class SourceHolder(view: View, override val adapter: SourceAdapter, val showButtons: Boolean) :
    FlexibleViewHolder(view, adapter),
    SlicedHolder {

    private val binding = SourceMainControllerCardItemBinding.bind(view)

    override val slice = Slice(binding.card).apply {
        setColor(adapter.cardBackground)
    }

    override val viewToSlice: View
        get() = binding.card

    init {
        binding.source_browse.setOnClickListener {
            adapter.browseClickListener.onBrowseClick(bindingAdapterPosition)
        }

        binding.source_latest.setOnClickListener {
            adapter.latestClickListener.onLatestClick(bindingAdapterPosition)
        }

        if (!showButtons) {
            binding.source_browse.gone()
            binding.source_latest.gone()
        }
    }

    fun bind(item: SourceItem) {
        val source = item.source
        setCardEdges(item)

        // Set source name
        binding.title.text = source.name

        // Set source icon
        itemView.post {
            val icon = source.icon()
            when {
                icon != null -> binding.image.setImageDrawable(icon)
                item.source.id == LocalSource.ID -> binding.image.setImageResource(R.mipmap.ic_local_source)
            }
        }

        binding.source_browse.setText(R.string.browse)
        if (source.supportsLatest && showButtons) {
            binding.source_latest.visible()
        } else {
            binding.source_latest.gone()
        }
    }
}
