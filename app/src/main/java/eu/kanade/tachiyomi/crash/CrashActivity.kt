package eu.kanade.tachiyomi.crash

import android.annotation.SuppressLint
import android.os.Bundle
import eu.kanade.tachiyomi.databinding.CrashBinding
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.copyToClipboard

class CrashActivity : BaseActivity<CrashBinding>() {
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = CrashBinding.inflate(layoutInflater)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        setContentView(binding.root)
        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent).toString()
        val version = GlobalExceptionHandler.getVersionFromIntent(intent)
        val text = "$version\n\n$exception"

        binding.crashBox.setText(text)
        binding.btnCopyCrash.setOnClickListener { this.copyToClipboard("Debug Info", text) }
    }
}
