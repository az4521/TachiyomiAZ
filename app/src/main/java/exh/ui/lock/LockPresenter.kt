package exh.ui.lock

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import uy.kohesive.injekt.injectLazy

class LockPresenter : BasePresenter<LockController>() {
    val prefs: PreferencesHelper by injectLazy()

    fun useFingerprint(context: Context) =
        prefs.eh_lockUseFingerprint().get() &&
            BiometricLock.isAvailable(context)
}
