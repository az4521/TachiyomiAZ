package eu.kanade.tachiyomi.ui.setting

import android.os.Handler
import android.text.InputType
import android.widget.Toast
import androidx.preference.PreferenceScreen
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.setActionButtonEnabled
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import com.kizitonwose.time.Interval
import com.kizitonwose.time.days
import com.kizitonwose.time.hours
import com.tfcporciuncula.flow.Preference
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.databinding.EhDialogCategoriesBinding
import eu.kanade.tachiyomi.databinding.EhDialogLanguagesBinding
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.preference.defaultValue
import eu.kanade.tachiyomi.util.preference.entriesRes
import eu.kanade.tachiyomi.util.preference.intListPreference
import eu.kanade.tachiyomi.util.preference.listPreference
import eu.kanade.tachiyomi.util.preference.multiSelectListPreference
import eu.kanade.tachiyomi.util.preference.onChange
import eu.kanade.tachiyomi.util.preference.onClick
import eu.kanade.tachiyomi.util.preference.preference
import eu.kanade.tachiyomi.util.preference.preferenceCategory
import eu.kanade.tachiyomi.util.preference.summaryRes
import eu.kanade.tachiyomi.util.preference.switchPreference
import eu.kanade.tachiyomi.util.system.toast
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import exh.eh.EHentaiUpdateWorker
import exh.eh.EHentaiUpdateWorkerConstants
import exh.eh.EHentaiUpdaterStats
import exh.favorites.FavoritesIntroDialog
import exh.favorites.LocalFavoritesStorage
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.getFlatMetadataForManga
import exh.metadata.nullIfBlank
import exh.uconfig.WarnConfigureDialogController
import exh.ui.login.LoginController
import exh.util.await
import exh.util.trans
import humanize.Humanize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy
import java.util.Date

/**
 * EH Settings fragment
 */

class SettingsEhController : SettingsController() {
    private val json: Json by injectLazy()
    private val db: DatabaseHelper by injectLazy()

    private fun Preference<*>.reconfigure(): Boolean {
        // Listen for change commit
        asFlow()
            .take(1) // Only listen for first commit
            .onEach {
                // Only listen for first change commit
                WarnConfigureDialogController.uploadSettings(router)
            }
            .launchIn(scope)

        // Always return true to save changes
        return true
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        with(screen) {
            title = "E-Hentai"

            preferenceCategory {
                title = "E-Hentai Website Account Settings"

                switchPreference {
                    title = "Enable ExHentai"
                    summaryOff = "Requires login"
                    key = PreferenceKeys.eh_enableExHentai
                    isPersistent = false
                    defaultValue = false
                    preferences.enableExhentai()
                        .asFlow()
                        .onEach {
                            isChecked = it
                        }
                        .launchIn(scope)

                    onChange { newVal ->
                        newVal as Boolean
                        if (!newVal) {
                            preferences.enableExhentai().set(false)
                            true
                        } else {
                            router.pushController(
                                RouterTransaction.with(LoginController())
                                    .pushChangeHandler(FadeChangeHandler())
                                    .popChangeHandler(FadeChangeHandler())
                            )
                            false
                        }
                    }
                }

                intListPreference {
                    title = "Use Hentai@Home Network"

                    key = PreferenceKeys.eh_enable_hah
                    if (preferences.eh_hathPerksCookies().get().isBlank()) {
                        summary = "Do you wish to load images through the Hentai@Home Network, if available? Disabling this option will reduce the amount of pages you are able to view\nOptions:\n - Any client (Recommended)\n - Default port clients only (Can be slower. Enable if behind firewall/proxy that blocks outgoing non-standard ports.)"
                        entries =
                            arrayOf(
                                "Any client (Recommended)",
                                "Default port clients only"
                            )
                        entryValues = arrayOf("0", "1")
                    } else {
                        summary = "Do you wish to load images through the Hentai@Home Network, if available? Disabling this option will reduce the amount of pages you are able to view\nOptions:\n - Any client (Recommended)\n - Default port clients only (Can be slower. Enable if behind firewall/proxy that blocks outgoing non-standard ports.)\n - No (Donator only. You will not be able to browse as many pages, enable only if having severe problems.)"
                        entries =
                            arrayOf(
                                "Any client (Recommended)",
                                "Default port clients only",
                                "No(will select Default port clients only if you are not a donator)"
                            )
                        entryValues = arrayOf("0", "1", "2")
                    }

                    onChange { preferences.useHentaiAtHome().reconfigure() }
                }.dependency = PreferenceKeys.eh_enableExHentai

                switchPreference {
                    title = "Show Japanese titles in search results"
                    summaryOn = "Currently showing Japanese titles in search results. Clear the chapter cache after changing this (in the Advanced section)"
                    summaryOff = "Currently showing English/Romanized titles in search results. Clear the chapter cache after changing this (in the Advanced section)"
                    key = "use_jp_title"
                    defaultValue = false

                    onChange { preferences.useJapaneseTitle().reconfigure() }
                }.dependency = PreferenceKeys.eh_enableExHentai

                switchPreference {
                    title = "Use original images"
                    summaryOn = "Currently using original images"
                    summaryOff = "Currently using resampled images"
                    key = PreferenceKeys.eh_useOrigImages
                    defaultValue = false

                    onChange { preferences.eh_useOriginalImages().reconfigure() }
                }.dependency = PreferenceKeys.eh_enableExHentai

                preference {
                    title = "Watched Tags"
                    summary = "Opens a webview to your E/ExHentai watched tags page"
                    onClick {
                        val intent =
                            if (preferences.enableExhentai().get()) {
                                WebViewActivity.newIntent(activity!!, url = "https://exhentai.org/mytags", title = "ExHentai Watched Tags")
                            } else {
                                WebViewActivity.newIntent(activity!!, url = "https://e-hentai.org/mytags", title = "E-Hentai Watched Tags")
                            }
                        startActivity(intent)
                    }
                }.dependency = PreferenceKeys.eh_enableExHentai

                preference {
                    title = "Tag Filtering Threshold"
                    key = PreferenceKeys.eh_tag_filtering_value
                    defaultValue = 0

                    summary = "You can soft filter tags by adding them to the \"My Tags\" E/ExHentai page with a negative weight. If a gallery has tags that add up to weight below this value, it is filtered from view. This threshold can be set between -9999 and 0. Currently: ${preferences.ehTagFilterValue().get()}"

                    onClick {
                        MaterialDialog(activity!!)
                            .title(text = "Tag Filtering Threshold")
                            .input(
                                inputType = InputType.TYPE_NUMBER_FLAG_SIGNED,
                                waitForPositiveButton = false,
                                allowEmpty = false
                            ) { dialog, number ->
                                val inputField = dialog.getInputField()
                                val value = number.toString().toIntOrNull()

                                if (value != null && value in -9999..0) {
                                    inputField.error = null
                                } else {
                                    inputField.error = "Must be between -9999 and 0!"
                                }
                                dialog.setActionButtonEnabled(WhichButton.POSITIVE, value != null && value in -9999..0)
                            }
                            .positiveButton(android.R.string.ok) {
                                val value = it.getInputField().text.toString().toInt()
                                preferences.ehTagFilterValue().set(value)
                                summary = "You can soft filter tags by adding them to the \"My Tags\" E/ExHentai page with a negative weight. If a gallery has tags that add up to weight below this value, it is filtered from view. This threshold can be set between 0 and -9999. Currently: $value"
                                preferences.ehTagFilterValue().reconfigure()
                            }
                            .show()
                    }
                }.dependency = PreferenceKeys.eh_enableExHentai

                preference {
                    title = "Tag Watching Threshold"
                    key = PreferenceKeys.eh_tag_watching_value
                    defaultValue = 0

                    summary = "Recently uploaded galleries will be included on the watched screen if it has at least one watched tag with positive weight, and the sum of weights on its watched tags add up to this value or higher. This threshold can be set between 0 and 9999. Currently: ${preferences.ehTagWatchingValue().get()}"

                    onClick {
                        MaterialDialog(activity!!)
                            .title(text = "Tag Watching Threshold")
                            .input(
                                inputType = InputType.TYPE_NUMBER_FLAG_SIGNED,
                                maxLength = 4,
                                waitForPositiveButton = false,
                                allowEmpty = false
                            ) { dialog, number ->
                                val inputField = dialog.getInputField()
                                val value = number.toString().toIntOrNull()

                                if (value != null && value in 0..9999) {
                                    inputField.error = null
                                } else {
                                    inputField.error = "Must be between 0 and 9999!"
                                }
                                dialog.setActionButtonEnabled(WhichButton.POSITIVE, value != null && value in 0..9999)
                            }
                            .positiveButton(android.R.string.ok) {
                                val value = it.getInputField().text.toString().toInt()
                                preferences.ehTagWatchingValue().set(value)
                                summary = "Recently uploaded galleries will be included on the watched screen if it has at least one watched tag with positive weight, and the sum of weights on its watched tags add up to this value or higher. This threshold can be set between 0 and 9999. Currently: $value"
                                preferences.ehTagWatchingValue().reconfigure()
                            }
                            .show()
                    }
                }.dependency = PreferenceKeys.eh_enableExHentai

                preference {
                    title = "Language Filtering"
                    summary = "If you wish to hide galleries in certain languages from the gallery list and searches, select them in the dialog that will popup.\nNote that matching galleries will never appear regardless of your search query.\nTldr checkmarked = exclude"

                    onClick {
                        MaterialDialog(activity!!)
                            .title(text = "Language Filtering")
                            .message(
                                text = "If you wish to hide galleries in certain languages from the gallery list and searches, select them in the dialog that will popup.\nNote that matching galleries will never appear regardless of your search query.\nTldr checkmarked = exclude"
                            )
                            .customView(R.layout.eh_dialog_languages, scrollable = true)
                            .positiveButton(android.R.string.ok) {
                                val customView = it.view.contentLayout.customView!!
                                val binding = EhDialogLanguagesBinding.bind(customView)

                                val languages =
                                    with(customView) {
                                        listOfNotNull(
                                            "${binding.japaneseOriginal.isChecked}*${binding.japaneseTranslated.isChecked}*${binding.japaneseRewrite.isChecked}",
                                            "${binding.englishOriginal.isChecked}*${binding.englishTranslated.isChecked}*${binding.englishRewrite.isChecked}",
                                            "${binding.chineseOriginal.isChecked}*${binding.chineseTranslated.isChecked}*${binding.chineseRewrite.isChecked}",
                                            "${binding.dutchOriginal.isChecked}*${binding.dutchTranslated.isChecked}*${binding.dutchRewrite.isChecked}",
                                            "${binding.frenchOriginal.isChecked}*${binding.frenchTranslated.isChecked}*${binding.frenchRewrite.isChecked}",
                                            "${binding.germanOriginal.isChecked}*${binding.germanTranslated.isChecked}*${binding.germanRewrite.isChecked}",
                                            "${binding.hungarianOriginal.isChecked}*${binding.hungarianTranslated.isChecked}*${binding.hungarianRewrite.isChecked}",
                                            "${binding.italianOriginal.isChecked}*${binding.italianTranslated.isChecked}*${binding.italianRewrite.isChecked}",
                                            "${binding.koreanOriginal.isChecked}*${binding.koreanTranslated.isChecked}*${binding.koreanRewrite.isChecked}",
                                            "${binding.polishOriginal.isChecked}*${binding.polishTranslated.isChecked}*${binding.polishRewrite.isChecked}",
                                            "${binding.portugueseOriginal.isChecked}*${binding.portugueseTranslated.isChecked}*${binding.portugueseRewrite.isChecked}",
                                            "${binding.russianOriginal.isChecked}*${binding.russianTranslated.isChecked}*${binding.russianRewrite.isChecked}",
                                            "${binding.spanishOriginal.isChecked}*${binding.spanishTranslated.isChecked}*${binding.spanishRewrite.isChecked}",
                                            "${binding.thaiOriginal.isChecked}*${binding.thaiTranslated.isChecked}*${binding.thaiRewrite.isChecked}",
                                            "${binding.vietnameseOriginal.isChecked}*${binding.vietnameseTranslated.isChecked}*${binding.vietnameseRewrite.isChecked}",
                                            "${binding.notAvailableOriginal.isChecked}*${binding.notAvailableTranslated.isChecked}*${binding.notAvailableRewrite.isChecked}",
                                            "${binding.otherOriginal.isChecked}*${binding.otherTranslated.isChecked}*${binding.otherRewrite.isChecked}"
                                        ).joinToString("\n")
                                    }

                                preferences.eh_settingsLanguages().set(languages)

                                preferences.eh_settingsLanguages().reconfigure()
                            }
                            .show {
                                val customView = this.view.contentLayout.customView!!
                                val binding = EhDialogLanguagesBinding.bind(customView)
                                val settingsLanguages = preferences.eh_settingsLanguages().get().split("\n")

                                val japanese = settingsLanguages[0].split("*").map { it.toBoolean() }
                                val english = settingsLanguages[1].split("*").map { it.toBoolean() }
                                val chinese = settingsLanguages[2].split("*").map { it.toBoolean() }
                                val dutch = settingsLanguages[3].split("*").map { it.toBoolean() }
                                val french = settingsLanguages[4].split("*").map { it.toBoolean() }
                                val german = settingsLanguages[5].split("*").map { it.toBoolean() }
                                val hungarian = settingsLanguages[6].split("*").map { it.toBoolean() }
                                val italian = settingsLanguages[7].split("*").map { it.toBoolean() }
                                val korean = settingsLanguages[8].split("*").map { it.toBoolean() }
                                val polish = settingsLanguages[9].split("*").map { it.toBoolean() }
                                val portuguese = settingsLanguages[10].split("*").map { it.toBoolean() }
                                val russian = settingsLanguages[11].split("*").map { it.toBoolean() }
                                val spanish = settingsLanguages[12].split("*").map { it.toBoolean() }
                                val thai = settingsLanguages[13].split("*").map { it.toBoolean() }
                                val vietnamese = settingsLanguages[14].split("*").map { it.toBoolean() }
                                val notAvailable =
                                    settingsLanguages[15].split("*").map { it.toBoolean() }
                                val other = settingsLanguages[16].split("*").map { it.toBoolean() }

                                with(customView) {
                                    binding.japaneseOriginal.isChecked = japanese[0]
                                    binding.japaneseTranslated.isChecked = japanese[1]
                                    binding.japaneseRewrite.isChecked = japanese[2]

                                    binding.englishOriginal.isChecked = english[0]
                                    binding.englishTranslated.isChecked = english[1]
                                    binding.englishRewrite.isChecked = english[2]

                                    binding.chineseOriginal.isChecked = chinese[0]
                                    binding.chineseTranslated.isChecked = chinese[1]
                                    binding.chineseRewrite.isChecked = chinese[2]

                                    binding.dutchOriginal.isChecked = dutch[0]
                                    binding.dutchTranslated.isChecked = dutch[1]
                                    binding.dutchRewrite.isChecked = dutch[2]

                                    binding.frenchOriginal.isChecked = french[0]
                                    binding.frenchTranslated.isChecked = french[1]
                                    binding.frenchRewrite.isChecked = french[2]

                                    binding.germanOriginal.isChecked = german[0]
                                    binding.germanTranslated.isChecked = german[1]
                                    binding.germanRewrite.isChecked = german[2]

                                    binding.hungarianOriginal.isChecked = hungarian[0]
                                    binding.hungarianTranslated.isChecked = hungarian[1]
                                    binding.hungarianRewrite.isChecked = hungarian[2]

                                    binding.italianOriginal.isChecked = italian[0]
                                    binding.italianTranslated.isChecked = italian[1]
                                    binding.italianRewrite.isChecked = italian[2]

                                    binding.koreanOriginal.isChecked = korean[0]
                                    binding.koreanTranslated.isChecked = korean[1]
                                    binding.koreanRewrite.isChecked = korean[2]

                                    binding.polishOriginal.isChecked = polish[0]
                                    binding.polishTranslated.isChecked = polish[1]
                                    binding.polishRewrite.isChecked = polish[2]

                                    binding.portugueseOriginal.isChecked = portuguese[0]
                                    binding.portugueseTranslated.isChecked = portuguese[1]
                                    binding.portugueseRewrite.isChecked = portuguese[2]

                                    binding.russianOriginal.isChecked = russian[0]
                                    binding.russianTranslated.isChecked = russian[1]
                                    binding.russianRewrite.isChecked = russian[2]

                                    binding.spanishOriginal.isChecked = spanish[0]
                                    binding.spanishTranslated.isChecked = spanish[1]
                                    binding.spanishRewrite.isChecked = spanish[2]

                                    binding.thaiOriginal.isChecked = thai[0]
                                    binding.thaiTranslated.isChecked = thai[1]
                                    binding.thaiRewrite.isChecked = thai[2]

                                    binding.vietnameseOriginal.isChecked = vietnamese[0]
                                    binding.vietnameseTranslated.isChecked = vietnamese[1]
                                    binding.vietnameseRewrite.isChecked = vietnamese[2]

                                    binding.notAvailableOriginal.isChecked = notAvailable[0]
                                    binding.notAvailableTranslated.isChecked = notAvailable[1]
                                    binding.notAvailableRewrite.isChecked = notAvailable[2]

                                    binding.otherOriginal.isChecked = other[0]
                                    binding.otherTranslated.isChecked = other[1]
                                    binding.otherRewrite.isChecked = other[2]
                                }
                            }
                    }
                }.dependency = PreferenceKeys.eh_enableExHentai

                preference {
                    title = "Front Page Categories"
                    summary = "What categories would you like to show by default on the front page and in searches? They can still be enabled by enabling their filters"

                    onClick {
                        MaterialDialog(activity!!)
                            .title(text = "Front Page Categories")
                            .message(
                                text = "What categories would you like to show by default on the front page and in searches? They can still be enabled by enabling their filters"
                            )
                            .customView(R.layout.eh_dialog_categories, scrollable = true)
                            .positiveButton {
                                val customView = it.view.contentLayout.customView!!
                                val binding = EhDialogCategoriesBinding.bind(customView)

                                with(customView) {
                                    preferences.eh_EnabledCategories().set(
                                        listOf(
                                            (!binding.doujinshiCheckbox.isChecked).toString(),
                                            (!binding.mangaCheckbox.isChecked).toString(),
                                            (!binding.artistCgCheckbox.isChecked).toString(),
                                            (!binding.gameCgCheckbox.isChecked).toString(),
                                            (!binding.westernCheckbox.isChecked).toString(),
                                            (!binding.nonHCheckbox.isChecked).toString(),
                                            (!binding.imageSetCheckbox.isChecked).toString(),
                                            (!binding.cosplayCheckbox.isChecked).toString(),
                                            (!binding.asianPornCheckbox.isChecked).toString(),
                                            (!binding.miscCheckbox.isChecked).toString()
                                        ).joinToString(",")
                                    )
                                }

                                preferences.eh_EnabledCategories().reconfigure()
                            }
                            .show {
                                val customView = this.view.contentLayout.customView!!
                                val binding = EhDialogCategoriesBinding.bind(customView)

                                with(customView) {
                                    val list = preferences.eh_EnabledCategories().get().split(",").map { !it.toBoolean() }
                                    binding.doujinshiCheckbox.isChecked = list[0]
                                    binding.mangaCheckbox.isChecked = list[1]
                                    binding.artistCgCheckbox.isChecked = list[2]
                                    binding.gameCgCheckbox.isChecked = list[3]
                                    binding.westernCheckbox.isChecked = list[4]
                                    binding.nonHCheckbox.isChecked = list[5]
                                    binding.imageSetCheckbox.isChecked = list[6]
                                    binding.cosplayCheckbox.isChecked = list[7]
                                    binding.asianPornCheckbox.isChecked = list[8]
                                    binding.miscCheckbox.isChecked = list[9]
                                }
                            }
                    }
                }.dependency = PreferenceKeys.eh_enableExHentai

                switchPreference {
                    defaultValue = false
                    key = PreferenceKeys.eh_watched_list_default_state
                    title = "Watched List Filter Default State"
                    summary = "When browsing ExHentai/E-Hentai should the watched list filter be enabled by default"
                }

                switchPreference {
                    defaultValue = true
                    key = PreferenceKeys.eh_secure_exh
                    title = "Secure ExHentai/E-Hentai"
                    summary = "Use the HTTPS version of ExHentai/E-Hentai."
                }

                listPreference {
                    defaultValue = "auto"
                    key = PreferenceKeys.eh_ehentai_quality
                    summary = "The quality of the downloaded images"
                    title = "Image quality"
                    entries =
                        arrayOf(
                            "Auto",
                            "2400x",
                            "1600x",
                            "1280x",
                            "980x",
                            "780x"
                        )
                    entryValues =
                        arrayOf(
                            "auto",
                            "ovrs_2400",
                            "ovrs_1600",
                            "high",
                            "med",
                            "low"
                        )

                    onChange { preferences.imageQuality().reconfigure() }
                }.dependency = PreferenceKeys.eh_enableExHentai
            }

            preferenceCategory {
                title = "Favorites sync"

                switchPreference {
                    title = "Disable favorites uploading"
                    summary = "Favorites are only downloaded from ExHentai. Any changes to favorites in the app will not be uploaded. Prevents accidental loss of favorites on ExHentai. Note that removals will still be downloaded (if you remove a favorites on ExHentai, it will be removed in the app as well)."
                    key = PreferenceKeys.eh_readOnlySync
                    defaultValue = false
                }

                preference {
                    title = "Show favorites sync notes"
                    summary = "Show some information regarding the favorites sync feature"

                    onClick {
                        activity?.let {
                            FavoritesIntroDialog().show(it)
                        }
                    }
                }

                switchPreference {
                    title = "Ignore sync errors when possible"
                    summary = "Do not abort immediately when encountering errors during the sync process. Errors will still be displayed when the sync is complete. Can cause loss of favorites in some cases. Useful when syncing large libraries."
                    key = PreferenceKeys.eh_lenientSync
                    defaultValue = false
                }

                preference {
                    title = "Force sync state reset"
                    summary = "Performs a full resynchronization on the next sync. Removals will not be synced. All favorites in the app will be re-uploaded to ExHentai and all favorites on ExHentai will be re-downloaded into the app. Useful for repairing sync after sync has been interrupted."

                    onClick {
                        activity?.let { activity ->
                            MaterialDialog(activity)
                                .title(R.string.eh_force_sync_reset_title)
                                .message(R.string.eh_force_sync_reset_message)
                                .positiveButton(android.R.string.yes) {
                                    LocalFavoritesStorage().apply {
                                        getRealm().use {
                                            it.trans {
                                                clearSnapshots(it)
                                            }
                                        }
                                    }
                                    activity.toast("Sync state reset", Toast.LENGTH_LONG)
                                }
                                .negativeButton(android.R.string.no)
                                .cancelable(false)
                                .show()
                        }
                    }
                }
            }

            preferenceCategory {
                title = "Gallery update checker"

                intListPreference {
                    key = PreferenceKeys.eh_autoUpdateFrequency
                    title = "Time between update batches"
                    entries =
                        arrayOf(
                            "Never update galleries",
                            "1 hour",
                            "2 hours",
                            "3 hours",
                            "6 hours",
                            "12 hours",
                            "24 hours",
                            "48 hours"
                        )
                    entryValues = arrayOf("0", "1", "2", "3", "6", "12", "24", "48")
                    defaultValue = "0"

                    preferences.eh_autoUpdateFrequency().asFlow()
                        .onEach { newVal ->
                            summary =
                                if (newVal == 0) {
                                    "${context.getString(
                                        R.string.app_name
                                    )} will currently never check galleries in your library for updates."
                                } else {
                                    "${context.getString(R.string.app_name)} checks/updates galleries in batches. " +
                                        "This means it will wait $newVal hour(s), check ${EHentaiUpdateWorkerConstants.UPDATES_PER_ITERATION} galleries," +
                                        " wait $newVal hour(s), check ${EHentaiUpdateWorkerConstants.UPDATES_PER_ITERATION} and so on..."
                                }
                        }
                        .launchIn(scope)

                    onChange { newValue ->
                        val interval = (newValue as String).toInt()
                        EHentaiUpdateWorker.scheduleBackground(context, interval)
                        true
                    }
                }

                multiSelectListPreference {
                    key = PreferenceKeys.eh_autoUpdateRestrictions
                    title = "Auto update restrictions"
                    entriesRes = arrayOf(R.string.wifi, R.string.charging)
                    entryValues = arrayOf("wifi", "ac")
                    summaryRes = R.string.pref_library_update_restriction_summary

                    preferences.eh_autoUpdateFrequency().asFlow()
                        .onEach { isVisible = it > 0 }
                        .launchIn(scope)

                    onChange {
                        // Post to event looper to allow the preference to be updated.
                        Handler().post { EHentaiUpdateWorker.scheduleBackground(context) }
                        true
                    }
                }

                preference {
                    title = "Show updater statistics"

                    onClick {
                        val progress =
                            MaterialDialog(context)
                                .message(R.string.eh_show_update_statistics_dialog)
                                .cancelable(false)
                        progress.show()

                        GlobalScope.launch(Dispatchers.IO) {
                            val updateInfo =
                                try {
                                    val stats =
                                        preferences.eh_autoUpdateStats().get().nullIfBlank()?.let {
                                            json.decodeFromString<EHentaiUpdaterStats>(it)
                                        }

                                    val statsText =
                                        if (stats != null) {
                                            "The updater last ran ${Humanize.naturalTime(
                                                Date(stats.startTime)
                                            )}, and checked ${stats.updateCount} out of the ${stats.possibleUpdates} galleries that were ready for checking."
                                        } else {
                                            "The updater has not ran yet."
                                        }

                                    val allMeta =
                                        db.getFavoriteMangaWithMetadata().await().filter {
                                            it.source == EH_SOURCE_ID || it.source == EXH_SOURCE_ID
                                        }.mapNotNull {
                                            db.getFlatMetadataForManga(it.id!!).await()
                                                ?.raise<EHentaiSearchMetadata>()
                                        }.toList()

                                    fun metaInRelativeDuration(duration: Interval<*>): Int {
                                        val durationMs = duration.inMilliseconds.longValue
                                        return allMeta.asSequence().filter {
                                            System.currentTimeMillis() - it.lastUpdateCheck < durationMs
                                        }.count()
                                    }

                                    """
                                    $statsText

                                    Galleries that were checked in the last:
                                    - hour: ${metaInRelativeDuration(1.hours)}
                                    - 6 hours: ${metaInRelativeDuration(6.hours)}
                                    - 12 hours: ${metaInRelativeDuration(12.hours)}
                                    - day: ${metaInRelativeDuration(1.days)}
                                    - 2 days: ${metaInRelativeDuration(2.days)}
                                    - week: ${metaInRelativeDuration(7.days)}
                                    - month: ${metaInRelativeDuration(30.days)}
                                    - year: ${metaInRelativeDuration(365.days)}
                                    """.trimIndent()
                                } finally {
                                    progress.dismiss()
                                }

                            withContext(Dispatchers.Main) {
                                MaterialDialog(context)
                                    .title(text = "Gallery updater statistics")
                                    .message(text = updateInfo)
                                    .positiveButton(android.R.string.ok)
                                    .show()
                            }
                        }
                    }
                }
            }
        }
}
