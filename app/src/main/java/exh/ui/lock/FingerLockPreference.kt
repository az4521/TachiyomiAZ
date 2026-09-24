package exh.ui.lock

import android.content.Context
import android.util.AttributeSet
import androidx.biometric.BiometricManager
import androidx.preference.SwitchPreferenceCompat
import com.afollestad.materialdialogs.MaterialDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.preference.onChange
import uy.kohesive.injekt.injectLazy

class FingerLockPreference
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    SwitchPreferenceCompat(context, attrs) {
    val prefs: PreferencesHelper by injectLazy()

    val fingerprintSupported
        get() = BiometricLock.isAvailable(context)

    val useFingerprint
        get() =
            fingerprintSupported &&
                prefs.eh_lockUseFingerprint().get()

    override fun onAttached() {
        super.onAttached()
        if (fingerprintSupported) {
            updateSummary()
            onChange {
                if (it as Boolean) {
                    tryChange()
                } else {
                    prefs.eh_lockUseFingerprint().set(false)
                }
                !it
            }
        } else {
            title = "Fingerprint unsupported"
            shouldDisableView = true
            summary =
                if (BiometricLock.status(context) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                    "No fingerprints enrolled!"
                } else {
                    "Fingerprint unlock is unsupported on this device!"
                }
            onChange { false }
        }
    }

    private fun updateSummary() {
        isChecked = useFingerprint
        title =
            if (isChecked) {
                "Fingerprint enabled"
            } else {
                "Fingerprint disabled"
            }
    }

    fun tryChange() {
        val activity = BiometricLock.findActivity(context) ?: return
        BiometricLock.authenticate(
            activity,
            title = "Fingerprint verification",
            negativeButtonText = context.getString(R.string.action_cancel),
            onSuccess = {
                prefs.eh_lockUseFingerprint().set(true)
                updateSummary()
            },
            onError = { message ->
                MaterialDialog(context)
                    .title(text = "Fingerprint verification failed!")
                    .message(text = message)
                    .positiveButton(android.R.string.ok)
                    .cancelable(true)
                    .cancelOnTouchOutside(false)
                    .show()
            }
        )
    }
}
