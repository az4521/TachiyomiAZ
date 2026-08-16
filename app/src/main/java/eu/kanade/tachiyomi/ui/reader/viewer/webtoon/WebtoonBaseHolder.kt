package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams
import eu.kanade.tachiyomi.ui.base.holder.BaseViewHolder
import kotlinx.coroutines.Job

abstract class WebtoonBaseHolder(
    view: View,
    protected val viewer: WebtoonViewer
) : BaseViewHolder(view) {
    /**
     * Context getter because it's used often.
     */
    val context: Context get() = itemView.context

    /**
     * Called when the view is recycled and being added to the view pool.
     */
    open fun recycle() {}

    /**
     * Adds a job to a list of jobs that will automatically be cancelled when the activity or the
     * reader is destroyed.
     */
    protected fun addJob(job: Job?) {
        job?.let { viewer.jobs.add(it) }
    }

    /**
     * Removes a job from the list of jobs, cancelling it.
     */
    protected fun removeJob(job: Job?) {
        job?.let {
            it.cancel()
            viewer.jobs.remove(it)
        }
    }

    /**
     * Extension method to set layout params to wrap content on this view.
     */
    protected fun View.wrapContent() {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }
}
