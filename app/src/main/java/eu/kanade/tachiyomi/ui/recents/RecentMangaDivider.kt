package eu.kanade.tachiyomi.ui.recents

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.view.marginStart
import com.google.android.material.card.MaterialCardView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isLTR

@SuppressLint("UseKtx")
class RecentMangaDivider(
    context: Context,
) : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
    private val divider: Drawable
    val padding: Int = 12.dpToPx

    init {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
        divider = a.getDrawable(0)!!
        a.recycle()
    }

    override fun onDraw(
        c: Canvas,
        parent: androidx.recyclerview.widget.RecyclerView,
        state: androidx.recyclerview.widget.RecyclerView.State,
    ) {
        val childCount = parent.childCount
        for (i in 0 until childCount - 1) {
            val child = parent.getChildAt(i)
            val holder = parent.getChildViewHolder(child)
            if (holder is RecentMangaHolder &&
                parent.getChildViewHolder(parent.getChildAt(i + 1)) is RecentMangaHolder
            ) {
                val isInContainer = holder.isContained()
                val params =
                    child.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
                val top = child.bottom + params.bottomMargin
                val bottom: Int
                val left: Int
                val right: Int
                if (isInContainer) {
                    bottom = top + divider.intrinsicHeight + 1.dpToPx
                    left = parent.paddingStart + padding
                    right = parent.width - parent.paddingEnd - padding
                } else {
                    bottom = top + divider.intrinsicHeight
                    left = parent.paddingStart + if (parent.context.resources.isLTR) padding else 0
                    right =
                        parent.width - parent.paddingEnd - if (!parent.context.resources.isLTR) padding else 0
                }
                divider.setBounds(left, top, right, bottom)
                divider.draw(c)
                if (isInContainer) {
                    c.drawColor(parent.context.getResourceColor(R.attr.background))
                }
            }
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: androidx.recyclerview.widget.RecyclerView,
        state: androidx.recyclerview.widget.RecyclerView.State,
    ) {
        val isInContainer = view.findViewById<MaterialCardView>(R.id.recent_card)?.marginStart != 0
        outRect.set(0, 0, 0, divider.intrinsicHeight + if (isInContainer) 1.dpToPx else 0)
    }
}
