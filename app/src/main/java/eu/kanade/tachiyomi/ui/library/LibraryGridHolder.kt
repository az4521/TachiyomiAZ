package eu.kanade.tachiyomi.ui.library

import android.util.TypedValue
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SourceCompactGridItemBinding
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.view.visibleIf
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Class used to hold the displayed data of a manga in the library, like the cover or the title.
 * All the elements from the layout file "item_source_grid" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @param listener a listener to react to single tap and long tap events.
 * @constructor creates a new library holder.
 */
open class LibraryGridHolder(
    private val view: View,
    adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>
) : LibraryHolder<SourceCompactGridItemBinding>(view, adapter) {
    override val binding = SourceCompactGridItemBinding.bind(view)

    private val preferences: PreferencesHelper = Injekt.get()

    var manga: Manga? = null

    // SY -->
    init {
        binding.playLayout.setOnClickListener {
            playButtonClicked()
        }
    }
    // SY <--

    /**
     * Method called from [LibraryCategoryAdapter.onBindViewHolder]. It updates the data for this
     * holder with the given manga.
     *
     * @param item the manga item to bind.
     */
    override fun onSetValues(item: LibraryItem) {
        val binding = binding

        // SY -->
        manga = item.manga
        // SY <--
        // Update the title of the manga.
        binding.title.text = item.manga.title

        // Update the unread count and its visibility.
        with(binding.unreadText) {
            visibleIf { item.unreadCount > 0 }
            text = item.unreadCount.toString()
        }
        // Update the download count and its visibility.
        with(binding.downloadText) {
            visibleIf { item.downloadCount > 0 }
            text = item.downloadCount.toString()
        }
        // set local visibility if its local manga
        binding.localText.visibleIf { item.manga.isLocal() }

        binding.card.radius =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                preferences.eh_library_corner_radius().get().toFloat(),
                view.context.resources.displayMetrics
            )

        // SY -->
        binding.playLayout.isVisible = (item.manga.unread > 0 && item.startReadingButton)
        // SY <--

        // Setting this via XML doesn't work
        // For rounded corners
        binding.card.clipToOutline = true

        // Update the cover.
        GlideApp.with(view.context).clear(binding.thumbnail)
        GlideApp.with(view.context)
            .load(item.manga.toMangaThumbnail())
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .centerCrop()
            .into(binding.thumbnail)
    }

    // SY -->
    private fun playButtonClicked() {
        manga?.let { (adapter as LibraryCategoryAdapter).controller.startReading(it, (adapter as LibraryCategoryAdapter)) }
    }
    // SY <--
}
