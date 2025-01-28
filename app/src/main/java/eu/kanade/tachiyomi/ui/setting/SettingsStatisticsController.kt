package eu.kanade.tachiyomi.ui.setting

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.util.preference.preferenceCategory
import eu.kanade.tachiyomi.util.preference.statisticsPreference
import eu.kanade.tachiyomi.util.preference.titleRes
import exh.debug.DebugFunctions
import uy.kohesive.injekt.injectLazy

/**
 * nhentai Settings fragment
 */

class SettingsStatisticsController : SettingsController() {
    val db: DatabaseHelper by injectLazy()
    val statsInfo = DebugFunctions.getStatisticsInfo()

    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        with(screen) {
            titleRes = R.string.pref_category_statistics
            preferenceCategory {
                titleRes = R.string.manga

                statisticsPreference(
                    statsInfo.mangaCount.toString(),
                    R.string.label_library
                )

                statisticsPreference(
                    (statsInfo.startedMangaCount - statsInfo.completedMangaCount).toString(),
                    R.string.currently_reading
                )

                statisticsPreference(
                    statsInfo.completedMangaCount.toString(),
                    R.string.completed
                )

                statisticsPreference(
                    statsInfo.localMangaCount.toString(),
                    R.string.local_source
                )
            }

            preferenceCategory {
                titleRes = R.string.chapters

                statisticsPreference( // total chapters
                    statsInfo.totalChapterCount.toString(),
                    R.string.action_sort_total
                )

                statisticsPreference( // read chapters
                    statsInfo.readChapterCount.toString(),
                    R.string.action_filter_read
                )

                statisticsPreference(
                    statsInfo.downloadedChapterCount.toString(),
                    R.string.action_filter_downloaded
                )
            }

            preferenceCategory {
                titleRes = R.string.pref_category_tracking

                statisticsPreference( // tracked manga count
                    statsInfo.trackedMangaCount.toString(),
                    R.string.action_filter_tracked
                )

                statisticsPreference( // average score
                    if (statsInfo.meanMangaScore.isNaN()) { "N/A" } else { String.format("%.1f", statsInfo.meanMangaScore) },
                    R.string.pref_average_score
                )
            }
        }
}
