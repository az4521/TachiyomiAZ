package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.ExtensionInstallBroadcast.Companion.EXTRA_SESSION_ID
import eu.kanade.tachiyomi.extension.util.ExtensionInstallBroadcast.Companion.PACKAGE_INSTALLED_ACTION
import eu.kanade.tachiyomi.extension.util.ExtensionInstallBroadcast.Companion.packageInstallStep
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.activityOptionsBackgroundOptions
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.injectLazy

/**
 * Broadcast used to install extensions, that receives callbacks from package installer.
 */
class ExtensionInstallBroadcast : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        try {
            if (PACKAGE_INSTALLED_ACTION == intent.action) {
                packageInstallStep(context, intent)
                return
            }

            val downloadId = intent.extras!!.getLong(ExtensionInstaller.EXTRA_DOWNLOAD_ID)
            val packageInstaller = context.packageManager.packageInstaller
            val data = UniFile.fromUri(context, intent.data).openInputStream()

            val params =
                SessionParams(
                    SessionParams.MODE_FULL_INSTALL,
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            session.openWrite("package", 0, -1).use { packageInSession ->
                data.copyTo(packageInSession)
            }

            val newIntent =
                Intent(context, ExtensionInstallBroadcast::class.java)
                    .setAction(PACKAGE_INSTALLED_ACTION)
                    .putExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, downloadId)
                    .putExtra(EXTRA_SESSION_ID, sessionId)

            val mutableFlag =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    downloadId.hashCode(),
                    newIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
                )
            val statusReceiver = pendingIntent.intentSender
            session.commit(statusReceiver)
            val extensionManager: ExtensionManager by injectLazy()
            extensionManager.setInstalling(downloadId, sessionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
            }
            data.close()
        } catch (error: Exception) {
            // Either install package can't be found (probably bots) or there's a security exception
            // with the download manager. Nothing we can workaround.
            context.toast(error.message)
        }
    }

    companion object {
        const val INSTALL_REQUEST_CODE = 500
        const val EXTRA_SESSION_ID = "ExtensionInstaller.extra.SESSION_ID"
        const val PACKAGE_INSTALLED_ACTION =
            "eu.kanade.tachiyomi.SESSION_API_PACKAGE_INSTALLED"

        fun packageInstallStep(
            context: Context,
            intent: Intent,
        ) {
            val extras = intent.extras ?: return
            if (PACKAGE_INSTALLED_ACTION == intent.action) {
                val downloadId = extras.getLong(ExtensionInstaller.EXTRA_DOWNLOAD_ID)
                val extensionManager: ExtensionManager by injectLazy()
                when (val status = extras.getInt(PackageInstaller.EXTRA_STATUS)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent = extras[Intent.EXTRA_INTENT] as? Intent
                        if (context is Activity) {
                            context.startActivity(confirmIntent)
                        } else {
                            context.startActivity(confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        extensionManager.setInstallationResult(downloadId, true)
                    }
                    PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED, PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_STORAGE -> {
                        extensionManager.setInstallationResult(downloadId, false)
                        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                            if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                                context.toast(R.string.extension_must_reinstall, Toast.LENGTH_LONG)
                            } else if (DeviceUtil.isMiui) {
                                context.toast(R.string.extensions_miui_warning, Toast.LENGTH_LONG)
                            } else {
                                context.toast(R.string.could_not_install_extension)
                            }
                        }
                    }
                    else -> {
                        extensionManager.setInstallationResult(downloadId, false)
                    }
                }
            }
        }
    }
}

/**
 * Activity used to install extensions, that receives callbacks from package installer.
 * Used when we need to prompt the user to install multiple apps
 */
class ExtensionInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (PACKAGE_INSTALLED_ACTION == intent.action) {
                packageInstallStep(this, intent)
                finish()
                return
            }

            val downloadId = intent.extras!!.getLong(ExtensionInstaller.EXTRA_DOWNLOAD_ID)
            val packageInstaller = packageManager.packageInstaller
            val data = UniFile.fromUri(this, intent.data).openInputStream()

            val params =
                SessionParams(
                    SessionParams.MODE_FULL_INSTALL,
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            session.openWrite("package", 0, -1).use { packageInSession ->
                data.copyTo(packageInSession)
            }
            val newIntent =
                Intent(this, ExtensionInstallActivity::class.java)
                    .setAction(PACKAGE_INSTALLED_ACTION)
                    .putExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, downloadId)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
            val mutableFlag =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    this,
                    downloadId.hashCode(),
                    newIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
                    activityOptionsBackgroundOptions()?.toBundle(),
                )
            val statusReceiver = pendingIntent.intentSender
            session.commit(statusReceiver)
            val extensionManager: ExtensionManager by injectLazy()
            extensionManager.setInstalling(downloadId, sessionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
            }
            data.close()
        } catch (error: Exception) {
            // Either install package can't be found (probably bots) or there's a security exception
            // with the download manager. Nothing we can workaround.
            toast(error.message)
        }
        finish()
    }
}
