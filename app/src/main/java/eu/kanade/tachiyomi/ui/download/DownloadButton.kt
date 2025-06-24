package eu.kanade.tachiyomi.ui.download

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.databinding.DownloadButtonBinding
import eu.kanade.tachiyomi.util.system.getResourceColor

class DownloadButton
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        var accentColor = context.getResourceColor(R.attr.colorSecondary)
            set(value) {
                field = value
                activeColor = ColorUtils.blendARGB(value, bgColor, 0.05f)
                downloadedColor = ColorUtils.blendARGB(value, cOnBgColor, 0.3f)
            }
        private val bgColor = context.getResourceColor(R.attr.background)
        private val cOnBgColor = context.getResourceColor(R.attr.colorOnBackground)
        private var activeColor = ColorUtils.blendARGB(accentColor, bgColor, 0.05f)
        private var downloadedColor = ColorUtils.blendARGB(accentColor, cOnBgColor, 0.3f)
        private val progressBGColor by lazy { ContextCompat.getColor(context, R.color.divider) }
        private val disabledColor by lazy {
            ContextCompat.getColor(context, R.color.material_on_surface_disabled)
        }
        private val downloadedTextColor = context.getResourceColor(R.attr.background)
        private val errorColor by lazy { ContextCompat.getColor(context, R.color.material_red_500) }
        private val filledCircle by lazy {
            ContextCompat.getDrawable(context, R.drawable.filled_circle)?.mutate()
        }
        private val borderCircle by lazy {
            ContextCompat.getDrawable(context, R.drawable.border_circle)?.mutate()
        }
        private val downloadDrawable by lazy {
            ContextCompat.getDrawable(context, R.drawable.ic_arrow_downward_24dp)?.mutate()
        }
        private val checkDrawable by lazy {
            ContextCompat.getDrawable(context, R.drawable.ic_check_24dp)?.mutate()
        }
        private val filledAnim by lazy {
            AnimatedVectorDrawableCompat.create(context, R.drawable.anim_outline_to_filled)
        }
        private val checkAnim by lazy {
            AnimatedVectorDrawableCompat.create(context, R.drawable.anim_dl_to_check_to_dl)
        }
        private var isAnimating = false
        private var iconAnimation: ObjectAnimator? = null

        private lateinit var binding: DownloadButtonBinding

        override fun onFinishInflate() {
            super.onFinishInflate()
            binding = DownloadButtonBinding.bind(this)
        }

        fun setDownloadStatus(
            state: Download.State,
            progress: Int = 0,
            animated: Boolean = false,
        ) {
            if (state != Download.State.DOWNLOADING) {
                iconAnimation?.cancel()
                binding.downloadIcon.alpha = 1f
                isAnimating = false
            }
            binding.downloadIcon.setImageDrawable(
                if (state == Download.State.CHECKED) {
                    checkDrawable
                } else {
                    downloadDrawable
                },
            )
            when (state) {
                Download.State.CHECKED -> {
                    binding.downloadProgress.isVisible = false
                    binding.downloadBorder.isVisible = true
                    binding.downloadBorder.setImageDrawable(filledCircle)
                    binding.downloadBorder.drawable.setTint(activeColor)
                    binding.downloadIcon.drawable.setTint(Color.WHITE)
                }
                Download.State.NOT_DOWNLOADED -> {
                    binding.downloadBorder.isVisible = true
                    binding.downloadProgress.isVisible = false
                    binding.downloadBorder.setImageDrawable(borderCircle)
                    binding.downloadBorder.drawable.setTint(activeColor)
                    binding.downloadIcon.drawable.setTint(activeColor)
                }
                Download.State.QUEUE -> {
                    binding.downloadBorder.isVisible = false
                    binding.downloadProgress.isVisible = true
                    binding.downloadProgress.isIndeterminate = true
                    binding.downloadIcon.drawable.setTint(disabledColor)
                    binding.downloadProgress.setIndicatorColor(disabledColor)
                    binding.downloadProgress.progress = 0
                }
                Download.State.DOWNLOADING -> {
                    binding.downloadBorder.isVisible = false
                    binding.downloadProgress.isVisible = true
                    binding.downloadBorder.setImageDrawable(borderCircle)
                    binding.downloadProgress.isIndeterminate = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        binding.downloadProgress.setProgress(progress, isAnimating)
                    } else {
                        binding.downloadProgress.progress = progress
                    }
                    binding.downloadProgress.trackColor = progressBGColor
                    binding.downloadProgress.setIndicatorColor(downloadedColor)
                    binding.downloadIcon.drawable.setTint(disabledColor)
                    if (!isAnimating) {
                        iconAnimation =
                            ObjectAnimator.ofFloat(binding.downloadIcon, "alpha", 1f, 0f).apply {
                                duration = 1000
                                repeatCount = ObjectAnimator.INFINITE
                                repeatMode = ObjectAnimator.REVERSE
                            }
                        iconAnimation?.start()
                        isAnimating = true
                    }
                }
                Download.State.DOWNLOADED -> {
                    binding.downloadProgress.isVisible = false
                    binding.downloadBorder.isVisible = true
                    binding.downloadBorder.drawable.setTint(downloadedColor)
                    if (animated) {
                        binding.downloadBorder.setImageDrawable(filledAnim)
                        binding.downloadIcon.setImageDrawable(checkAnim)
                        filledAnim?.start()
                        val alphaAnimation = ValueAnimator.ofArgb(disabledColor, downloadedTextColor)
                        alphaAnimation.addUpdateListener { valueAnimator ->
                            binding.downloadIcon.drawable.setTint(valueAnimator.animatedValue as Int)
                        }
                        alphaAnimation.doOnEnd {
                            binding.downloadIcon.drawable.setTint(downloadedTextColor)
                            checkAnim?.start()
                        }
                        alphaAnimation.duration = 150
                        alphaAnimation.start()
                        binding.downloadBorder.drawable.setTint(downloadedColor)
                    } else {
                        binding.downloadBorder.setImageDrawable(filledCircle)
                        binding.downloadBorder.drawable.setTint(downloadedColor)
                        binding.downloadIcon.drawable.setTint(downloadedTextColor)
                    }
                }
                Download.State.ERROR -> {
                    binding.downloadProgress.isVisible = false
                    binding.downloadBorder.isVisible = true
                    binding.downloadBorder.setImageDrawable(borderCircle)
                    binding.downloadBorder.drawable.setTint(errorColor)
                    binding.downloadIcon.drawable.setTint(errorColor)
                }
            }
        }
    }
