package eu.kanade.tachiyomi.ui.category

import android.view.View
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.databinding.CategoriesItemBinding
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder

/**
 * Holder used to display category items.
 *
 * @param view The view used by category items.
 * @param adapter The adapter containing this holder.
 */
class CategoryHolder(view: View, val adapter: CategoryAdapter) : BaseFlexibleViewHolder(view, adapter) {
    private val binding = CategoriesItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
    }

    /**
     * Binds this holder with the given category.
     *
     * @param category The category to bind.
     */
    fun bind(category: Category) {
        // DON'T capitalize category names
        binding.title.text = category.name // .capitalize()
    }

    /**
     * Called when an item is released.
     *
     * @param position The position of the released item.
     */
    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.onItemReleaseListener.onItemReleased(position)
    }
}
