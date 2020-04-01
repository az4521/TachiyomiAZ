package eu.kanade.tachiyomi.ui.setting

import android.os.Build
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.preference.*
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsReaderController : SettingsController() {

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = R.string.pref_category_reader

        intListPreference {
            key = Keys.defaultViewer
            titleRes = R.string.pref_viewer_type
            entriesRes = arrayOf(R.string.left_to_right_viewer, R.string.right_to_left_viewer,
                    R.string.vertical_viewer, R.string.webtoon_viewer)
            entryValues = arrayOf("1", "2", "3", "4")
            defaultValue = "1"
            summary = "%s"
        }
        intListPreference {
            key = Keys.imageScaleType
            titleRes = R.string.pref_image_scale_type
            entriesRes = arrayOf(R.string.scale_type_fit_screen, R.string.scale_type_stretch,
                    R.string.scale_type_fit_width, R.string.scale_type_fit_height,
                    R.string.scale_type_original_size, R.string.scale_type_smart_fit)
            entryValues = arrayOf("1", "2", "3", "4", "5", "6")
            defaultValue = "1"
            summary = "%s"
        }
        intListPreference {
            key = Keys.zoomStart
            titleRes = R.string.pref_zoom_start
            entriesRes = arrayOf(R.string.zoom_start_automatic, R.string.zoom_start_left,
                    R.string.zoom_start_right, R.string.zoom_start_center)
            entryValues = arrayOf("1", "2", "3", "4")
            defaultValue = "1"
            summary = "%s"
        }
        intListPreference {
            key = Keys.rotation
            titleRes = R.string.pref_rotation_type
            entriesRes = arrayOf(R.string.rotation_free, R.string.rotation_lock,
                    R.string.rotation_force_portrait, R.string.rotation_force_landscape)
            entryValues = arrayOf("1", "2", "3", "4")
            defaultValue = "1"
            summary = "%s"
        }
        intListPreference {
            key = Keys.readerTheme
            titleRes = R.string.pref_reader_theme
            entriesRes = arrayOf(R.string.white_background, R.string.black_background)
            entryValues = arrayOf("0", "1")
            defaultValue = "0"
            summary = "%s"
        }
        intListPreference {
            key = Keys.doubleTapAnimationSpeed
            titleRes = R.string.pref_double_tap_anim_speed
            entries = arrayOf(context.getString(R.string.double_tap_anim_speed_0), context.getString(R.string.double_tap_anim_speed_fast), context.getString(R.string.double_tap_anim_speed_normal))
            entryValues = arrayOf("1", "250", "500") // using a value of 0 breaks the image viewer, so min is 1
            defaultValue = "500"
            summary = "%s"
        }
        switchPreference {
            key = Keys.skipRead
            titleRes = R.string.pref_skip_read_chapters
            defaultValue = false
        }
        switchPreference {
            key = Keys.fullscreen
            titleRes = R.string.pref_fullscreen
            defaultValue = true
        }
        switchPreference {
            key = Keys.keepScreenOn
            titleRes = R.string.pref_keep_screen_on
            defaultValue = true
        }
        switchPreference {
            key = Keys.showPageNumber
            titleRes = R.string.pref_show_page_number
            defaultValue = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            switchPreference {
                key = Keys.trueColor
                titleRes = R.string.pref_true_color
                defaultValue = false
            }
        }
        // EXH -->
        intListPreference {
            key = Keys.eh_readerThreads
            title = "Download threads"
            entries = arrayOf("1", "2", "3", "4", "5")
            entryValues = entries
            defaultValue = "2"
            summary = "Higher values can speed up image downloading significantly, but can also trigger bans. Recommended value is 2 or 3. Current value is: %s"
        }
        switchPreference {
            key = Keys.eh_aggressivePageLoading
            title = "Aggressively load pages"
            summary = "Slowly download the entire gallery while reading instead of just loading the pages you are viewing."
            defaultValue = false
        }
        switchPreference {
            key = Keys.eh_readerInstantRetry
            title = "Skip queue on retry"
            summary = "Normally, pressing the retry button on a failed download will wait until the downloader has finished downloading the last page before beginning to re-download the failed page. Enabling this will force the downloader to begin re-downloading the failed page as soon as you press the retry button."
            defaultValue = true
        }
        listPreference {
            key = Keys.eh_cacheSize
            title = "Reader cache size"
            entryValues = arrayOf(
                    "50",
                    "75",
                    "100",
                    "150",
                    "250",
                    "500",
                    "750",
                    "1000",
                    "1500",
                    "2000",
                    "2500",
                    "3000",
                    "3500",
                    "4000",
                    "4500",
                    "5000"
            )
            entries = arrayOf(
                    "50 MB",
                    "75 MB",
                    "100 MB",
                    "150 MB",
                    "250 MB",
                    "500 MB",
                    "750 MB",
                    "1 GB",
                    "1.5 GB",
                    "2 GB",
                    "2.5 GB",
                    "3 GB",
                    "3.5 GB",
                    "4 GB",
                    "4.5 GB",
                    "5 GB"
            )
            defaultValue = "75"
            summary = "The amount of images to save on device while reading. Higher values will result in a smoother reading experience, at the cost of higher disk space usage"
        }
        switchPreference {
            key = Keys.eh_preserveReadingPosition
            title = "Preserve reading position on read manga"
            defaultValue = false
        }
        // EXH <--

        switchPreference {
            key = Keys.alwaysShowChapterTransition
            titleRes = R.string.pref_always_show_chapter_transition
            defaultValue = true
        }

        preferenceCategory {
            titleRes = R.string.pager_viewer

            switchPreference {
                key = Keys.enableTransitions
                titleRes = R.string.pref_page_transitions
                defaultValue = true
            }
            switchPreference {
                key = Keys.cropBorders
                titleRes = R.string.pref_crop_borders
                defaultValue = false
            }
        }
        preferenceCategory {
            titleRes = R.string.webtoon_viewer

            switchPreference {
                key = Keys.cropBordersWebtoon
                titleRes = R.string.pref_crop_borders
                defaultValue = false
            }
        }
        preferenceCategory {
            titleRes = R.string.pref_reader_navigation

            switchPreference {
                key = Keys.readWithTapping
                titleRes = R.string.pref_read_with_tapping
                defaultValue = true
            }
            switchPreference {
                key = Keys.readWithLongTap
                titleRes = R.string.pref_read_with_long_tap
                defaultValue = true
            }
            switchPreference {
                key = Keys.readWithVolumeKeys
                titleRes = R.string.pref_read_with_volume_keys
                defaultValue = false
            }
            switchPreference {
                key = Keys.readWithVolumeKeysInverted
                titleRes = R.string.pref_read_with_volume_keys_inverted
                defaultValue = false
            }.apply { dependency = Keys.readWithVolumeKeys }
        }
    }

}
