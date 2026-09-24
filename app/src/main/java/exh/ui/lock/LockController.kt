package exh.ui.lock

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.afollestad.materialdialogs.MaterialDialog
import com.andrognito.pinlockview.PinLockListener
import com.mattprecious.swirl.SwirlView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.ActivityLockBinding
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import exh.util.dpToPx
import uy.kohesive.injekt.injectLazy

class LockController : NucleusController<ActivityLockBinding, LockPresenter>() {
    val prefs: PreferencesHelper by injectLazy()

    private var biometricPrompt: BiometricPrompt? = null

    override fun inflateView(
        inflater: LayoutInflater,
        container: ViewGroup
    ): View {
        binding = ActivityLockBinding.inflate(inflater)
        return binding.root
    }

    override fun createPresenter() = LockPresenter()

    override fun getTitle() = "Application locked"

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        if (!lockEnabled(prefs)) {
            closeLock()
            return
        }

        with(view) {
            // Setup pin lock
            binding.pinLockView.attachIndicatorDots(binding.indicatorDots)

            binding.pinLockView.pinLength = prefs.eh_lockLength().get()
            binding.pinLockView.setPinLockListener(
                object : PinLockListener {
                    override fun onEmpty() {}

                    override fun onComplete(pin: String) {
                        if (sha512(pin, prefs.eh_lockSalt().get()) == prefs.eh_lockHash().get()) {
                            // Yay!
                            closeLock()
                        } else {
                            MaterialDialog(context)
                                .title(text = "PIN code incorrect")
                                .message(text = "The PIN code you entered is incorrect. Please try again.")
                                .cancelable(true)
                                .cancelOnTouchOutside(true)
                                .positiveButton(android.R.string.ok)
                                .show()
                            binding.pinLockView.resetPinLockView()
                        }
                    }

                    override fun onPinChange(
                        pinLength: Int,
                        intermediatePin: String?
                    ) {}
                }
            )
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)

        with(view) {
            // Fingerprint
            if (presenter.useFingerprint(context)) {
                binding.swirlContainer.visibility = View.VISIBLE
                binding.swirlContainer.removeAllViews()
                val icon =
                    SwirlView(context).apply {
                        val size = dpToPx(context, 60)
                        layoutParams =
                            (
                                layoutParams ?: ViewGroup.LayoutParams(
                                    size,
                                    size
                                )
                                ).apply {
                                width = size
                                height = size

                                val pSize = dpToPx(context, 8)
                                setPadding(pSize, pSize, pSize, pSize)
                            }
                        val lockColor = resolvColor(android.R.attr.windowBackground)
                        setBackgroundColor(lockColor)
                        val bgColor = resolvColor(android.R.attr.colorBackground)
                        // Disable elevation if lock color is same as background color
                        if (lockColor == bgColor) {
                            binding.swirlContainer.cardElevation = 0f
                        }
                        setState(SwirlView.State.OFF, true)
                    }
                binding.swirlContainer.addView(icon)
                // The system prompt covers the PIN pad; dismissing it falls back to the PIN, and
                // tapping the icon brings the prompt back.
                icon.setOnClickListener { showBiometricPrompt(icon) }
                showBiometricPrompt(icon)
            } else {
                binding.swirlContainer.visibility = View.GONE
            }
        }
    }

    private fun showBiometricPrompt(icon: SwirlView) {
        val activity = activity as? FragmentActivity ?: return
        biometricPrompt?.cancelAuthentication()
        icon.setState(SwirlView.State.ON)
        biometricPrompt =
            BiometricLock.authenticate(
                activity,
                title = "Unlock",
                negativeButtonText = "Use PIN",
                onSuccess = { closeLock() },
                onError = { message ->
                    MaterialDialog(activity)
                        .title(text = "Fingerprint error!")
                        .message(text = message)
                        .cancelable(false)
                        .cancelOnTouchOutside(false)
                        .positiveButton(android.R.string.ok)
                        .show()
                    icon.setState(SwirlView.State.ERROR)
                }
            )
    }

    private fun resolvColor(color: Int): Int {
        val typedVal = TypedValue()
        activity!!.theme!!.resolveAttribute(color, typedVal, true)
        return typedVal.data
    }

    override fun onDetach(view: View) {
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        super.onDetach(view)
    }

    fun closeLock() {
        router.popCurrentController()
    }

    override fun handleBack() = true
}
