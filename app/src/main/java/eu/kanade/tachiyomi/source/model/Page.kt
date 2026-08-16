package eu.kanade.tachiyomi.source.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow

open class Page(
    val index: Int,
    var url: String = "",
    var imageUrl: String? = null,
    @Transient var uri: Uri? = null // Deprecated but can't be deleted due to extensions
) : ProgressListener {
    val number: Int
        get() = index + 1

    @Transient
    @Volatile
    var status: Int = 0
        set(value) {
            field = value
            statusFlow?.tryEmit(value)
            statusCallback?.invoke(this)
        }

    @Transient
    @Volatile
    var progress: Int = 0
        set(value) {
            field = value
            statusCallback?.invoke(this)
        }

    @Transient
    private var statusFlow: MutableSharedFlow<Int>? = null

    @Transient
    private var statusCallback: ((Page) -> Unit)? = null

    override fun update(
        bytesRead: Long,
        contentLength: Long,
        done: Boolean
    ) {
        progress =
            if (contentLength > 0) {
                (100 * bytesRead / contentLength).toInt()
            } else {
                -1
            }
    }

    fun setStatusFlow(flow: MutableSharedFlow<Int>?) {
        this.statusFlow = flow
    }

    fun setStatusCallback(f: ((Page) -> Unit)?) {
        statusCallback = f
    }

    companion object {
        const val QUEUE = 0
        const val LOAD_PAGE = 1
        const val DOWNLOAD_IMAGE = 2
        const val READY = 3
        const val ERROR = 4
    }
}
