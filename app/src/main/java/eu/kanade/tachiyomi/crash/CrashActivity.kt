package eu.kanade.tachiyomi.crash

import android.annotation.SuppressLint
import android.os.Bundle
import eu.kanade.tachiyomi.databinding.CrashBinding
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.CrashLogUtil

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
        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent)

        binding.crashBox.setText(exception.toString())
        binding.btnCopyCrash.setOnClickListener { CrashLogUtil(applicationContext).dumpLogs(exception) }
    }
}
