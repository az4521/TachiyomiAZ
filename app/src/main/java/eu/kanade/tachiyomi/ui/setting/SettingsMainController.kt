package eu.kanade.tachiyomi.ui.setting

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.util.preference.iconRes
import eu.kanade.tachiyomi.util.preference.iconTint
import eu.kanade.tachiyomi.util.preference.onClick
import eu.kanade.tachiyomi.util.preference.preference
import eu.kanade.tachiyomi.util.preference.titleRes
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.openInBrowser

class SettingsMainController : SettingsController() {
    init {
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        inflater: MenuInflater
    ) {
        inflater.inflate(R.menu.settings, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_help -> activity?.openInBrowser(URL_HELP)
        }

        return super.onOptionsItemSelected(item)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        with(screen) {
            titleRes = R.string.label_settings

            val tintColor = context.getResourceColor(R.attr.colorAccent)

            preference {
                iconRes = R.drawable.ic_tune_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_general
                onClick { navigateTo(SettingsGeneralController()) }
            }
            preference {
                iconRes = R.drawable.ic_query_stats_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_statistics
                onClick { navigateTo(SettingsStatisticsController()) }
            }
            preference {
                iconRes = R.drawable.ic_collections_bookmark_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_library
                onClick { navigateTo(SettingsLibraryController()) }
            }
            preference {
                iconRes = R.drawable.ic_chrome_reader_mode_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_reader
                onClick { navigateTo(SettingsReaderController()) }
            }
            preference {
                iconRes = R.drawable.ic_file_download_black_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_downloads
                onClick { navigateTo(SettingsDownloadController()) }
            }
            preference {
                iconRes = R.drawable.ic_sync_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_tracking
                onClick { navigateTo(SettingsTrackingController()) }
            }
            preference {
                iconRes = R.drawable.ic_explore_24dp
                iconTint = tintColor
                titleRes = R.string.browse
                onClick { navigateTo(SettingsBrowseController()) }
            }
            preference {
                iconRes = R.drawable.ic_backup_24dp
                iconTint = tintColor
                titleRes = R.string.backup
                onClick { navigateTo(SettingsBackupController()) }
            }
            if (preferences.eh_isHentaiEnabled().get()) {
                preference {
                    iconRes = R.drawable.eh_ic_ehlogo_red_24dp
                    iconTint = tintColor
                    titleRes = R.string.pref_category_eh
                    onClick { navigateTo(SettingsEhController()) }
                }
                preference {
                    iconRes = R.drawable.eh_ic_nhlogo_color
                    iconTint = tintColor
                    titleRes = R.string.pref_category_nh
                    onClick { navigateTo(SettingsNhController()) }
                }
            }
            preference {
                iconRes = R.drawable.ic_code_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_advanced
                onClick { navigateTo(SettingsAdvancedController()) }
            }
            preference {
                iconRes = R.drawable.ic_info_24dp
                iconTint = tintColor
                titleRes = R.string.pref_category_about
                onClick { navigateTo(SettingsAboutController()) }
            }
        }

    private fun navigateTo(controller: SettingsController) {
        router.pushController(controller.withFadeTransaction())
    }

    companion object {
        private const val URL_HELP = "https://mihon.app/docs/guides/troubleshooting/"
    }
}
