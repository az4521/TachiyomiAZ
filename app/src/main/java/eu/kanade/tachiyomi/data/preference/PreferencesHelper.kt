package eu.kanade.tachiyomi.data.preference

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import androidx.preference.PreferenceManager
import com.f2prateek.rx.preferences.Preference
import com.f2prateek.rx.preferences.RxSharedPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys
import eu.kanade.tachiyomi.data.preference.PreferenceValues as Values
import eu.kanade.tachiyomi.data.track.TrackService
import exh.ui.migration.MigrationStatus
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

fun <T> Preference<T>.getOrDefault(): T = get() ?: defaultValue()!!

fun Preference<Boolean>.invert(): Boolean = getOrDefault().let { set(!it); !it }

private class DateFormatConverter : Preference.Adapter<DateFormat> {
    override fun get(key: String, preferences: SharedPreferences): DateFormat {
        val dateFormat = preferences.getString(Keys.dateFormat, "")!!

        if (dateFormat != "") {
            return SimpleDateFormat(dateFormat, Locale.getDefault())
        }

        return DateFormat.getDateInstance(DateFormat.SHORT)
    }

    override fun set(key: String, value: DateFormat, editor: SharedPreferences.Editor) {
        // No-op
    }
}

class PreferencesHelper(val context: Context) {

    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val rxPrefs = RxSharedPreferences.create(prefs)

    private val defaultDownloadsDir = Uri.fromFile(
            File(Environment.getExternalStorageDirectory().absolutePath + File.separator +
                    context.getString(R.string.app_name), "downloads"))

    private val defaultBackupDir = Uri.fromFile(
            File(Environment.getExternalStorageDirectory().absolutePath + File.separator +
                    context.getString(R.string.app_name), "backup"))

    fun startScreen() = prefs.getInt(Keys.startScreen, 1)

    fun secureScreen() = rxPrefs.getBoolean(Keys.secureScreen, false)

    fun hideNotificationContent() = prefs.getBoolean(Keys.hideNotificationContent, false)

    fun clear() = prefs.edit().clear().apply()

    fun themeMode() = rxPrefs.getString(Keys.themeMode, Values.THEME_MODE_SYSTEM)

    fun themeLight() = prefs.getString(Keys.themeLight, Values.THEME_DARK_DEFAULT)

    fun themeDark() = prefs.getString(Keys.themeDark, Values.THEME_LIGHT_DEFAULT)

    fun rotation() = rxPrefs.getInteger(Keys.rotation, 1)

    fun pageTransitions() = rxPrefs.getBoolean(Keys.enableTransitions, true)

    fun doubleTapAnimSpeed() = rxPrefs.getInteger(Keys.doubleTapAnimationSpeed, 500)

    fun showPageNumber() = rxPrefs.getBoolean(Keys.showPageNumber, true)

    fun trueColor() = rxPrefs.getBoolean(Keys.trueColor, false)

    fun fullscreen() = rxPrefs.getBoolean(Keys.fullscreen, true)

    fun cutoutShort() = rxPrefs.getBoolean(Keys.cutoutShort, true)

    fun keepScreenOn() = rxPrefs.getBoolean(Keys.keepScreenOn, true)

    fun customBrightness() = rxPrefs.getBoolean(Keys.customBrightness, false)

    fun customBrightnessValue() = rxPrefs.getInteger(Keys.customBrightnessValue, 0)

    fun colorFilter() = rxPrefs.getBoolean(Keys.colorFilter, false)

    fun colorFilterValue() = rxPrefs.getInteger(Keys.colorFilterValue, 0)

    fun colorFilterMode() = rxPrefs.getInteger(Keys.colorFilterMode, 0)

    fun defaultViewer() = prefs.getInt(Keys.defaultViewer, 1)

    fun imageScaleType() = rxPrefs.getInteger(Keys.imageScaleType, 1)

    fun zoomStart() = rxPrefs.getInteger(Keys.zoomStart, 1)

    fun readerTheme() = rxPrefs.getInteger(Keys.readerTheme, 1)

    fun cropBorders() = rxPrefs.getBoolean(Keys.cropBorders, false)

    fun cropBordersWebtoon() = rxPrefs.getBoolean(Keys.cropBordersWebtoon, false)

    fun webtoonSidePadding() = rxPrefs.getInteger(Keys.webtoonSidePadding, 0)

    fun readWithTapping() = rxPrefs.getBoolean(Keys.readWithTapping, true)

    fun readWithLongTap() = rxPrefs.getBoolean(Keys.readWithLongTap, true)

    fun readWithVolumeKeys() = rxPrefs.getBoolean(Keys.readWithVolumeKeys, false)

    fun readWithVolumeKeysInverted() = rxPrefs.getBoolean(Keys.readWithVolumeKeysInverted, false)

    fun portraitColumns() = rxPrefs.getInteger(Keys.portraitColumns, 0)

    fun landscapeColumns() = rxPrefs.getInteger(Keys.landscapeColumns, 0)

    fun updateOnlyNonCompleted() = prefs.getBoolean(Keys.updateOnlyNonCompleted, false)

    fun autoUpdateTrack() = prefs.getBoolean(Keys.autoUpdateTrack, true)

    fun lastUsedCatalogueSource() = rxPrefs.getLong(Keys.lastUsedCatalogueSource, -1)

    fun lastUsedCategory() = rxPrefs.getInteger(Keys.lastUsedCategory, 0)

    fun lastVersionCode() = rxPrefs.getInteger("last_version_code", 0)

    fun catalogueAsList() = rxPrefs.getBoolean(Keys.catalogueAsList, false)

    fun enabledLanguages() = rxPrefs.getStringSet(Keys.enabledLanguages, setOf("all", "en", Locale.getDefault().language))

    fun sourceSorting() = rxPrefs.getInteger(Keys.sourcesSort, 0)

    fun trackUsername(sync: TrackService) = prefs.getString(Keys.trackUsername(sync.id), "")

    fun trackPassword(sync: TrackService) = prefs.getString(Keys.trackPassword(sync.id), "")

    fun setTrackCredentials(sync: TrackService, username: String, password: String) {
        prefs.edit()
                .putString(Keys.trackUsername(sync.id), username)
                .putString(Keys.trackPassword(sync.id), password)
                .apply()
    }

    fun trackToken(sync: TrackService) = rxPrefs.getString(Keys.trackToken(sync.id), "")

    fun anilistScoreType() = rxPrefs.getString("anilist_score_type", "POINT_10")

    fun backupsDirectory() = rxPrefs.getString(Keys.backupDirectory, defaultBackupDir.toString())

    fun dateFormat() = rxPrefs.getObject(Keys.dateFormat, DateFormat.getDateInstance(DateFormat.SHORT), DateFormatConverter())

    fun downloadsDirectory() = rxPrefs.getString(Keys.downloadsDirectory, defaultDownloadsDir.toString())

    fun downloadOnlyOverWifi() = prefs.getBoolean(Keys.downloadOnlyOverWifi, true)

    fun numberOfBackups() = rxPrefs.getInteger(Keys.numberOfBackups, 1)

    fun backupInterval() = rxPrefs.getInteger(Keys.backupInterval, 0)

    fun removeAfterReadSlots() = prefs.getInt(Keys.removeAfterReadSlots, -1)

    fun removeAfterMarkedAsRead() = prefs.getBoolean(Keys.removeAfterMarkedAsRead, false)

    fun libraryUpdateInterval() = rxPrefs.getInteger(Keys.libraryUpdateInterval, 0)

    fun libraryUpdateRestriction() = prefs.getStringSet(Keys.libraryUpdateRestriction, emptySet())

    fun libraryUpdateCategories() = rxPrefs.getStringSet(Keys.libraryUpdateCategories, emptySet())

    fun libraryUpdatePrioritization() = rxPrefs.getInteger(Keys.libraryUpdatePrioritization, 0)

    fun libraryAsList() = rxPrefs.getBoolean(Keys.libraryAsList, false)

    fun downloadBadge() = rxPrefs.getBoolean(Keys.downloadBadge, false)

    // J2K converted from boolean to integer
    fun filterDownloaded() = rxPrefs.getInteger(Keys.filterDownloaded, 0)

    fun filterUnread() = rxPrefs.getInteger(Keys.filterUnread, 0)

    fun filterCompleted() = rxPrefs.getInteger(Keys.filterCompleted, 0)

    fun librarySortingMode() = rxPrefs.getInteger(Keys.librarySortingMode, 0)

    fun librarySortingAscending() = rxPrefs.getBoolean("library_sorting_ascending", true)

    fun automaticUpdates() = prefs.getBoolean(Keys.automaticUpdates, true)

    fun hiddenCatalogues() = rxPrefs.getStringSet("hidden_catalogues", mutableSetOf())

    fun pinnedCatalogues() = rxPrefs.getStringSet("pinned_catalogues", emptySet())

    fun automaticExtUpdates() = rxPrefs.getBoolean(Keys.automaticExtUpdates, true)

    fun extensionUpdatesCount() = rxPrefs.getInteger("ext_updates_count", 0)

    fun lastExtCheck() = rxPrefs.getLong("last_ext_check", 0)

    fun downloadNew() = rxPrefs.getBoolean(Keys.downloadNew, false)

    fun downloadNewCategories() = rxPrefs.getStringSet(Keys.downloadNewCategories, emptySet())

    fun lang() = prefs.getString(Keys.lang, "")

    fun defaultCategory() = prefs.getInt(Keys.defaultCategory, -1)

    fun skipRead() = prefs.getBoolean(Keys.skipRead, false)

    fun skipFiltered() = prefs.getBoolean(Keys.skipFiltered, false)

    fun migrateFlags() = rxPrefs.getInteger("migrate_flags", Int.MAX_VALUE)

    fun trustedSignatures() = rxPrefs.getStringSet("trusted_signatures", emptySet())

    fun alwaysShowChapterTransition() = rxPrefs.getBoolean(Keys.alwaysShowChapterTransition, true)

    // --> AZ J2K CHERRYPICKING

    fun defaultMangaOrder() = rxPrefs.getString("default_manga_order", "")

    fun upgradeFilters() {
        val filterDl = rxPrefs.getBoolean(Keys.filterDownloaded, false).getOrDefault()
        val filterUn = rxPrefs.getBoolean(Keys.filterUnread, false).getOrDefault()
        val filterCm = rxPrefs.getBoolean(Keys.filterCompleted, false).getOrDefault()
        filterDownloaded().set(if (filterDl) 1 else 0)
        filterUnread().set(if (filterUn) 1 else 0)
        filterCompleted().set(if (filterCm) 1 else 0)
    }

    // <--

    // --> EH
    fun enableExhentai() = rxPrefs.getBoolean(Keys.eh_enableExHentai, false)

    fun secureEXH() = rxPrefs.getBoolean("secure_exh", true)

    fun imageQuality() = rxPrefs.getString("ehentai_quality", "auto")

    fun useHentaiAtHome() = rxPrefs.getBoolean("enable_hah", true)

    fun useJapaneseTitle() = rxPrefs.getBoolean("use_jp_title", false)

    fun eh_useOriginalImages() = rxPrefs.getBoolean(Keys.eh_useOrigImages, false)

    fun ehSearchSize() = rxPrefs.getString("ex_search_size", "rc_0")

    fun thumbnailRows() = rxPrefs.getString("ex_thumb_rows", "tr_2")

    fun migrateLibraryAsked() = rxPrefs.getBoolean("ex_migrate_library3", false)

    fun migrationStatus() = rxPrefs.getInteger("migration_status", MigrationStatus.NOT_INITIALIZED)

    fun hasPerformedURLMigration() = rxPrefs.getBoolean("performed_url_migration", false)

    // EH Cookies
    fun memberIdVal() = rxPrefs.getString("eh_ipb_member_id", "")

    fun passHashVal() = rxPrefs.getString("eh_ipb_pass_hash", "")
    fun igneousVal() = rxPrefs.getString("eh_igneous", "")
    fun eh_ehSettingsProfile() = rxPrefs.getInteger(Keys.eh_ehSettingsProfile, -1)
    fun eh_exhSettingsProfile() = rxPrefs.getInteger(Keys.eh_exhSettingsProfile, -1)
    fun eh_settingsKey() = rxPrefs.getString(Keys.eh_settingsKey, "")
    fun eh_sessionCookie() = rxPrefs.getString(Keys.eh_sessionCookie, "")
    fun eh_hathPerksCookies() = rxPrefs.getString(Keys.eh_hathPerksCookie, "")

    // Lock
    fun eh_lockHash() = rxPrefs.getString(Keys.eh_lock_hash, null)

    fun eh_lockSalt() = rxPrefs.getString(Keys.eh_lock_salt, null)

    fun eh_lockLength() = rxPrefs.getInteger(Keys.eh_lock_length, -1)

    fun eh_lockUseFingerprint() = rxPrefs.getBoolean(Keys.eh_lock_finger, false)

    fun eh_lockManually() = rxPrefs.getBoolean(Keys.eh_lock_manually, false)

    fun eh_nh_useHighQualityThumbs() = rxPrefs.getBoolean(Keys.eh_nh_useHighQualityThumbs, false)

    fun eh_showSyncIntro() = rxPrefs.getBoolean(Keys.eh_showSyncIntro, true)

    fun eh_readOnlySync() = rxPrefs.getBoolean(Keys.eh_readOnlySync, false)

    fun eh_lenientSync() = rxPrefs.getBoolean(Keys.eh_lenientSync, false)

    fun eh_ts_aspNetCookie() = rxPrefs.getString(Keys.eh_ts_aspNetCookie, "")

    fun eh_showSettingsUploadWarning() = rxPrefs.getBoolean(Keys.eh_showSettingsUploadWarning, true)

    fun eh_expandFilters() = rxPrefs.getBoolean(Keys.eh_expandFilters, false)

    fun eh_readerThreads() = rxPrefs.getInteger(Keys.eh_readerThreads, 2)

    fun eh_readerInstantRetry() = rxPrefs.getBoolean(Keys.eh_readerInstantRetry, true)

    fun eh_utilAutoscrollInterval() = rxPrefs.getFloat(Keys.eh_utilAutoscrollInterval, 3f)

    fun eh_cacheSize() = rxPrefs.getString(Keys.eh_cacheSize, "75")

    fun eh_preserveReadingPosition() = rxPrefs.getBoolean(Keys.eh_preserveReadingPosition, false)

    fun eh_autoSolveCaptchas() = rxPrefs.getBoolean(Keys.eh_autoSolveCaptchas, false)

    fun eh_delegateSources() = rxPrefs.getBoolean(Keys.eh_delegateSources, true)

    fun eh_lastVersionCode() = rxPrefs.getInteger("eh_last_version_code", 0)

    fun eh_savedSearches() = rxPrefs.getStringSet("eh_saved_searches", emptySet())

    fun eh_logLevel() = rxPrefs.getInteger(Keys.eh_logLevel, 0)

    fun eh_enableSourceBlacklist() = rxPrefs.getBoolean(Keys.eh_enableSourceBlacklist, true)

    fun eh_autoUpdateFrequency() = rxPrefs.getInteger(Keys.eh_autoUpdateFrequency, 1)

    fun eh_autoUpdateRequirements() = prefs.getStringSet(Keys.eh_autoUpdateRestrictions, emptySet())

    fun eh_autoUpdateStats() = rxPrefs.getString(Keys.eh_autoUpdateStats, "")

    fun eh_aggressivePageLoading() = rxPrefs.getBoolean(Keys.eh_aggressivePageLoading, false)

    fun eh_hl_useHighQualityThumbs() = rxPrefs.getBoolean(Keys.eh_hl_useHighQualityThumbs, false)
}
