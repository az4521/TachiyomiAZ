@file:Suppress("PackageDirectoryMismatch")

package androidx.recyclerview.widget

import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * Layout manager used by the webtoon viewer. Item prefetch is disabled because the extra layout
 * space feature is used which allows setting the image even if the holder is not visible,
 * avoiding (in most cases) black views when they are visible.
 *
 * This layout manager uses the same package name as the support library in order to use a package
 * protected method.
 */
class WebtoonLayoutManager(
    activity: ReaderActivity,
    orientation: Int = RecyclerView.VERTICAL,
    reverse: Boolean = false
) : LinearLayoutManager(activity, orientation, reverse) {
    /**
     * Extra layout space is set to half the screen size along the scroll direction.
     */
    private val extraLayoutSpace =
        activity.resources.displayMetrics.let {
            if (orientation == HORIZONTAL) it.widthPixels else it.heightPixels
        } / 2

    init {
        isItemPrefetchEnabled = false
    }

    /**
     * Returns the custom extra layout space.
     */
    override fun getExtraLayoutSpace(state: RecyclerView.State): Int {
        return extraLayoutSpace
    }

    /**
     * Returns the position of the last item whose end side, in reading order, is visible on screen.
     * With a reversed layout the reading end of an item is its left (or top) edge.
     */
    fun findLastEndVisibleItemPosition(): Int {
        ensureLayoutState()
        val helper = mOrientationHelper
        var result = NO_POSITION
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val endVisible =
                if (reverseLayout) {
                    helper.getDecoratedStart(child) >= helper.startAfterPadding
                } else {
                    helper.getDecoratedEnd(child) <= helper.endAfterPadding
                }
            if (endVisible) {
                result = maxOf(result, getPosition(child))
            }
        }
        return result
    }
}
