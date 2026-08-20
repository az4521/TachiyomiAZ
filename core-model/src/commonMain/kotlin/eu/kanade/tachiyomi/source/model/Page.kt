package eu.kanade.tachiyomi.source.model

import eu.kanade.tachiyomi.network.ProgressListener
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableSharedFlow

open class Page(
    val index: Int,
    var url: String = "",
    var imageUrl: String? = null,
    // Android-only, and deprecated there: it points at a downloaded file. Kept because old
    // extensions construct pages with it. @Transient is dropped -- Page is not Serializable, so
    // it never had an effect.
    var uri: PlatformUri? = null
) : ProgressListener {
    val number: Int
        get() = index + 1

    @Volatile
    var status: Int = 0
        set(value) {
            field = value
            statusFlow?.tryEmit(value)
            statusCallback?.invoke(this)
        }

    @Volatile
    var progress: Int = 0
        set(value) {
            field = value
            statusCallback?.invoke(this)
        }

    private var statusFlow: MutableSharedFlow<Int>? = null

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
