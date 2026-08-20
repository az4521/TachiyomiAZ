package eu.kanade.tachiyomi.ui.setting.track

import android.content.Intent
import android.os.Bundle
import android.view.Gravity.CENTER
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy

class ShikimoriLoginActivity : AppCompatActivity() {
    private val trackManager: TrackManager by injectLazy()

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        val view = ProgressBar(this)
        setContentView(view, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, CENTER))

        val code = intent.data?.getQueryParameter("code")
        if (code != null) {
            lifecycleScope.launch {
                try {
                    trackManager.shikimori.login(code)
                } catch (e: Throwable) {
                    // The previous Rx chain returned to settings on both success and
                    // failure; the tracker itself logs out on a failed login.
                }
                returnToSettings()
            }
        } else {
            trackManager.shikimori.logout()
            returnToSettings()
        }
    }

    private fun returnToSettings() {
        finish()

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }
}
