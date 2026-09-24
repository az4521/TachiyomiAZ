package exh.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Fingerprint unlock through androidx.biometric. It uses the platform BiometricPrompt on Android 9+
 * and only falls back to FingerprintManager on 6-8, where that class still exists.
 */
object BiometricLock {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun status(context: Context): Int = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    fun isAvailable(context: Context) = status(context) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system prompt. [onError] is not called when the user dismisses the prompt or picks
     * the negative button, only for real failures such as a lockout.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (CharSequence) -> Unit
    ): BiometricPrompt {
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> Unit
                        else -> onError(errString)
                    }
                }
            }
        val promptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(negativeButtonText)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        return BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).apply {
            authenticate(promptInfo)
        }
    }

    fun findActivity(context: Context): FragmentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return current
            current = current.baseContext
        }
        return null
    }
}
