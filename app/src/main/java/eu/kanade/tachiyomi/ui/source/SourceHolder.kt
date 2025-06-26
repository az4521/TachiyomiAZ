package eu.kanade.tachiyomi.ui.source

import android.content.res.ColorStateList
import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.shape.CornerFamily
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.SourceItemBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.compatToolTipText

class SourceHolder(
    view: View,
    val adapter: SourceAdapter,
) : BaseFlexibleViewHolder(view, adapter) {
    val binding = SourceItemBinding.bind(view)

    init {
        binding.sourcePin.setOnClickListener {
            adapter.sourceListener.onPinClick(flexibleAdapterPosition)
        }
        binding.sourceLatest.setOnClickListener {
            adapter.sourceListener.onLatestClick(flexibleAdapterPosition)
        }
    }

    fun bind(item: SourceItem) {
        val source = item.source
        // setCardEdges(item)

        val underPinnedSection = item.header?.code?.equals(SourcePresenter.PINNED_KEY) ?: false
        val underLastUsedSection = item.header?.code?.equals(SourcePresenter.LAST_USED_KEY) ?: false
        val isPinned = item.isPinned ?: underPinnedSection
        val showLanguage = source.includeLangInName(adapter.enabledLanguages, adapter.extensionManager)
        val sourceName = if (showLanguage && (underPinnedSection || underLastUsedSection)) source.toString() else source.name
        binding.title.text = sourceName

        binding.sourcePin.apply {
            iconTint =
                ColorStateList.valueOf(
                    context.getResourceColor(
                        if (isPinned) {
                            R.attr.colorSecondary
                        } else {
                            android.R.attr.textColorSecondary
                        },
                    ),
                )
            compatToolTipText = context.getString(if (isPinned) R.string.unpin else R.string.pin)
            contentDescription = context.getString(if (isPinned) R.string.unpin else R.string.pin)
            setIconResource(
                if (isPinned) {
                    R.drawable.ic_pin_24dp
                } else {
                    R.drawable.ic_pin_outline_24dp
                },
            )
        }

        // Set circle letter image.
        itemView.post {
            val icon = source.icon()
            when {
                icon != null -> binding.sourceImage.setImageDrawable(icon)
                item.source.id == LocalSource.ID -> binding.sourceImage.setImageResource(R.mipmap.ic_local_source)
            }
        }

        binding.sourceLatest.isVisible = source.supportsLatest
    }

    override fun getFrontView(): View = binding.sourceCard

    override fun getRearStartView(): View = binding.startView

    override fun getRearEndView(): View = binding.endView

    fun setCorners(
        top: Boolean,
        bottom: Boolean,
    ) {
        val shapeModel =
            binding.sourceCard.shapeAppearanceModel
                .toBuilder()
                .apply {
                    setTopLeftCorner(CornerFamily.ROUNDED, if (top) 12f.dpToPx else 2f.dpToPx)
                    setTopRightCorner(CornerFamily.ROUNDED, if (top) 12f.dpToPx else 2f.dpToPx)
                    setBottomLeftCorner(CornerFamily.ROUNDED, if (bottom) 12f.dpToPx else 2f.dpToPx)
                    setBottomRightCorner(CornerFamily.ROUNDED, if (bottom) 12f.dpToPx else 2f.dpToPx)
                }.build()
        binding.sourceCard.shapeAppearanceModel = shapeModel
        binding.startView.shapeAppearanceModel = shapeModel
        binding.endView.shapeAppearanceModel = shapeModel
    }
}
