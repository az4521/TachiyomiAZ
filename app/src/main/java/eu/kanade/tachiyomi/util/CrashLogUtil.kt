package eu.kanade.tachiyomi.util

import android.content.Context
import android.net.Uri
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId

class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager = Injekt.get()
) {
    private val notificationBuilder =
        context.notificationBuilder(Notifications.CHANNEL_CRASH_LOGS) {
            setSmallIcon(R.drawable.ic_tachi)
        }

    fun dumpLogs(exception: Throwable? = null) {
        try {
            val file = File(context.externalCacheDir, "tachiyomi_crash_logs.txt")
            if (file.exists()) {
                file.delete()
            }
            file.createNewFile()
            file.appendText(getDebugInfo() + "\n\n")
            exception?.let { file.appendText("$it\n\n") }

            Runtime.getRuntime().exec("logcat *:E -d -f ${file.absolutePath}").waitFor()

            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (e: IOException) {
            context.toast("Failed to get logs")
        }
    }

    fun getDebugInfo(): String {
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${OffsetDateTime.now(ZoneId.systemDefault())}
        """.trimIndent()
    }

    private fun showNotification(uri: Uri) {
        context.notificationManager.cancel(Notifications.ID_CRASH_LOGS)

        with(notificationBuilder) {
            setContentTitle(context.getString(R.string.crash_log_saved))

            // Clear old actions if they exist
            // clearActions()

            addAction(
                R.drawable.ic_folder_24dp,
                context.getString(R.string.action_open_log),
                NotificationReceiver.openErrorLogPendingActivity(context, uri)
            )

            context.notificationManager.notify(Notifications.ID_CRASH_LOGS, build())
        }
    }
}
