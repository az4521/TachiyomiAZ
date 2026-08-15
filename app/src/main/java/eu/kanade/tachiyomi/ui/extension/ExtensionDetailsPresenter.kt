package eu.kanade.tachiyomi.ui.extension

import android.os.Bundle
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.lang.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionDetailsPresenter(
    val pkgName: String,
    private val extensionManager: ExtensionManager = Injekt.get()
) : BasePresenter<ExtensionDetailsController>() {
    val extension = extensionManager.installedExtensions.find { it.pkgName == pkgName }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        bindToUninstalledExtension()
    }

    private fun bindToUninstalledExtension() {
        extensionManager.getInstalledExtensionsObservable()
            .asFlow()
            .drop(1)
            .filter { extensions -> extensions.none { it.pkgName == pkgName } }
            .take(1)
            .collectLatestCache(onNext = { view, _ -> view.onExtensionUninstalled() })
    }

    fun uninstallExtension() {
        val extension = extension ?: return
        extensionManager.uninstallExtension(extension.pkgName)
    }
}
