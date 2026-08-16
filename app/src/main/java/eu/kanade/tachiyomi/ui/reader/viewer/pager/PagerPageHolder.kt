package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.NoTransition
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressBar
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig.ZoomType
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.visible
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import exh.util.isInNightMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import java.io.InputStream

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    val viewer: PagerViewer,
    val page: ReaderPage
) : FrameLayout(viewer.activity), ViewPagerAdapter.PositionableView {
    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressBar = createProgressBar()

    /**
     * Image view that supports subsampling on zoom.
     */
    private var subsamplingImageView: SubsamplingScaleImageView? = null

    /**
     * Simple image view only used on GIFs.
     */
    private var imageView: ImageView? = null

    /**
     * Retry button used to allow retrying.
     */
    private var retryButton: PagerButton? = null

    /**
     * Error layout to show when the image fails to decode.
     */
    private var decodeErrorLayout: ViewGroup? = null

    /**
     * Subscription for status changes of the page.
     */
    /**
     * Scope for this holder's jobs, cancelled when the view leaves the window.
     */
    private val scope = MainScope()

    private var statusJob: Job? = null

    /**
     * Subscription for progress changes of the page.
     */
    private var progressJob: Job? = null

    /**
     * Subscription used to read the header of the image. This is needed in order to instantiate
     * the appropiate image view depending if the image is animated (GIF).
     */
    private var readImageHeaderJob: Job? = null

    init {
        addView(progressBar)
        observeStatus()
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unsubscribeProgress()
        unsubscribeStatus()
        unsubscribeReadImageHeader()
        scope.cancel()
        subsamplingImageView?.setOnImageEventListener(null)
    }

    /**
     * Observes the status of the page and notify the changes.
     *
     * @see processStatus
     */
    private fun observeStatus() {
        statusJob?.cancel()

        val loader = page.chapter.pageLoader ?: return
        statusJob =
            loader.getPage(page)
                .onEach { processStatus(it) }
                .launchIn(scope)
    }

    /**
     * Observes the progress of the page and updates view.
     */
    private fun observeProgress() {
        progressJob?.cancel()

        progressJob =
            scope.launch {
                // Polls instead of observing because Page.progress has no change notification.
                // The loop only advances after the update runs, so it cannot outpace the view the
                // way onBackpressureLatest was there to prevent.
                var lastProgress = -1
                while (isActive) {
                    val value = page.progress
                    if (value != lastProgress) {
                        lastProgress = value
                        progressBar.setProgress(value)
                    }
                    delay(100)
                }
            }
    }

    /**
     * Called when the status of the page changes.
     *
     * @param status the new status of the page.
     */
    private fun processStatus(status: Int) {
        when (status) {
            Page.QUEUE -> setQueued()
            Page.LOAD_PAGE -> setLoading()
            Page.DOWNLOAD_IMAGE -> {
                observeProgress()
                setDownloading()
            }
            Page.READY -> {
                setImage()
                unsubscribeProgress()
            }
            Page.ERROR -> {
                setError()
                unsubscribeProgress()
            }
        }
    }

    /**
     * Unsubscribes from the status subscription.
     */
    private fun unsubscribeStatus() {
        statusJob?.cancel()
        statusJob = null
    }

    /**
     * Unsubscribes from the progress subscription.
     */
    private fun unsubscribeProgress() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * Unsubscribes from the read image header subscription.
     */
    private fun unsubscribeReadImageHeader() {
        readImageHeaderJob?.cancel()
        readImageHeaderJob = null
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressBar.visible()
        retryButton?.gone()
        decodeErrorLayout?.gone()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressBar.visible()
        retryButton?.gone()
        decodeErrorLayout?.gone()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        progressBar.visible()
        retryButton?.gone()
        decodeErrorLayout?.gone()
    }

    /**
     * Called when the page is ready.
     */
    private fun setImage() {
        progressBar.visible()
        progressBar.completeAndFadeOut()
        retryButton?.gone()
        decodeErrorLayout?.gone()

        unsubscribeReadImageHeader()
        val streamFn = page.stream ?: return

        var openStream: InputStream? = null
        readImageHeaderJob =
            scope.launch {
                try {
                    val isAnimated =
                        withIOContext {
                            val stream = streamFn().buffered(16)
                            openStream = stream
                            ImageUtil.findImageType(stream) == ImageUtil.ImageType.GIF
                        }

                    if (!isAnimated) {
                        // SY -->
                        if (viewer.config.readerTheme >= 3) {
                            val imageView = initSubsamplingImageView()
                            if (page.bg != null && page.bgType ==
                                getBGType(
                                        viewer.config.readerTheme,
                                        context
                                    )
                            ) {
                                imageView.setImage(ImageSource.inputStream(openStream!!))
                                imageView.background = page.bg
                            }
                            // if the user switches to automatic when pages are already cached, the bg needs to be loaded
                            else {
                                val bytesArray = openStream!!.readBytes()
                                val bytesStream = bytesArray.inputStream()
                                imageView.setImage(ImageSource.inputStream(bytesStream))
                                bytesStream.close()

                                launchUI {
                                    imageView.background = setBG(bytesArray)
                                    page.bg = imageView.background
                                    page.bgType = getBGType(viewer.config.readerTheme, context)
                                }
                            }
                        } else {
                            initSubsamplingImageView().setImage(ImageSource.inputStream(openStream!!))
                        }
                        // SY <--
                    } else {
                        initImageView().setImage(openStream!!)
                    }

                    // The views read from the stream lazily, so it must stay open until this
                    // job is cancelled. Observable.never + doOnUnsubscribe did the same.
                    awaitCancellation()
                } finally {
                    openStream?.close()
                }
            }
    }

    // SY -->
    private suspend fun setBG(bytesArray: ByteArray): Drawable {
        return withContext(Dispatchers.Default) {
            val preferences by injectLazy<PreferencesHelper>()
            ImageUtil.autoSetBackground(
                BitmapFactory.decodeByteArray(
                    bytesArray,
                    0,
                    bytesArray.size
                ),
                preferences.readerTheme().get() == 3,
                context
            )
        }
    }
    // SY <--

    /**
     * Called when the page has an error.
     */
    private fun setError() {
        progressBar.gone()
        initRetryButton().visible()
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressBar.gone()
    }

    /**
     * Called when an image fails to decode.
     */
    private fun onImageDecodeError() {
        progressBar.gone()
        initDecodeErrorLayout().visible()
    }

    /**
     * Creates a new progress bar.
     */
    @SuppressLint("PrivateResource")
    private fun createProgressBar(): ReaderProgressBar {
        return ReaderProgressBar(context, null).apply {
            val size = 48.dpToPx
            layoutParams =
                LayoutParams(size, size).apply {
                    gravity = Gravity.CENTER
                }
        }
    }

    /**
     * Initializes a subsampling scale view.
     */
    private fun initSubsamplingImageView(): SubsamplingScaleImageView {
        if (subsamplingImageView != null) return subsamplingImageView!!

        val config = viewer.config

        subsamplingImageView =
            SubsamplingScaleImageView(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
                setMaxTileSize(viewer.activity.maxBitmapSize)
                setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                setDoubleTapZoomDuration(config.doubleTapAnimDuration)
                setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                setMinimumScaleType(config.imageScaleType)
                setMinimumDpi(90)
                setMinimumTileDpi(180)
                setCropBorders(config.imageCropBorders)
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            when (config.imageZoomType) {
                                ZoomType.Left -> setScaleAndCenter(scale, PointF(0f, 0f))
                                ZoomType.Right -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0f))
                                ZoomType.Center -> setScaleAndCenter(scale, center.also { it?.y = 0f })
                            }
                            onImageDecoded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageDecodeError()
                        }
                    }
                )
            }
        addView(subsamplingImageView)
        return subsamplingImageView!!
    }

    /**
     * Initializes an image view, used for GIFs.
     */
    private fun initImageView(): ImageView {
        if (imageView != null) return imageView!!

        imageView =
            PhotoView(context, null).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
                adjustViewBounds = true
                setZoomTransitionDuration(viewer.config.doubleTapAnimDuration)
                setScaleLevels(1f, 2f, 3f)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1f) {
                                setScale(1f, e.x, e.y, true)
                            } else {
                                setScale(2f, e.x, e.y, true)
                            }
                            return true
                        }
                    }
                )
            }
        addView(imageView)
        return imageView!!
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initRetryButton(): PagerButton {
        if (retryButton != null) return retryButton!!

        retryButton =
            PagerButton(context, viewer).apply {
                layoutParams =
                    LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER
                    }
                setText(R.string.action_retry)
                setOnClickListener {
                    page.chapter.pageLoader?.retryPage(page)
                }
            }
        addView(retryButton)
        return retryButton!!
    }

    /**
     * Initializes a decode error layout.
     */
    private fun initDecodeErrorLayout(): ViewGroup {
        if (decodeErrorLayout != null) return decodeErrorLayout!!

        val margins = 8.dpToPx

        val decodeLayout =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.VERTICAL
            }
        decodeErrorLayout = decodeLayout

        TextView(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    setMargins(margins, margins, margins, margins)
                }
            gravity = Gravity.CENTER
            setText(R.string.decode_image_error)

            decodeLayout.addView(this)
        }

        PagerButton(context, viewer).apply {
            layoutParams =
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    setMargins(margins, margins, margins, margins)
                }
            setText(R.string.action_retry)
            setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }

            decodeLayout.addView(this)
        }

        val imageUrl = page.imageUrl
        if (imageUrl.orEmpty().startsWith("http", true)) {
            PagerButton(context, viewer).apply {
                layoutParams =
                    LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        setMargins(margins, margins, margins, margins)
                    }
                setText(R.string.action_open_in_web_view)
                setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl!!)
                    context.startActivity(intent)
                }

                decodeLayout.addView(this)
            }
        }

        addView(decodeLayout)
        return decodeLayout
    }

    /**
     * Extension method to set a [stream] into this ImageView.
     */
    private fun ImageView.setImage(stream: InputStream) {
        GlideApp.with(this)
            .asGif()
            .load(stream)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .transition(DrawableTransitionOptions.with(NoTransition.getFactory()))
            .listener(
                object : RequestListener<GifDrawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<GifDrawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        onImageDecodeError()
                        return false
                    }

                    override fun onResourceReady(
                        resource: GifDrawable,
                        model: Any,
                        target: Target<GifDrawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        resource.setLoopCount(GifDrawable.LOOP_INTRINSIC)
                        onImageDecoded()
                        return false
                    }
                }
            )
            .into(this)
    }

    // SY -->
    companion object {
        fun getBGType(
            readerTheme: Int,
            context: Context
        ): Int {
            return if (readerTheme == 3) {
                if (context.isInNightMode()) 2 else 1
            } else {
                0
            }
        }
    }
    // SY <--
}
