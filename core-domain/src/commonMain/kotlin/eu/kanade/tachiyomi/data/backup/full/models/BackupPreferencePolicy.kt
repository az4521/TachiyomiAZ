package eu.kanade.tachiyomi.data.backup.full.models

/**
 * Which settings go into a backup, and which of the two fields they go into.
 *
 * Field 104 is Mihon's and TachiyomiSY's, so anything written there is read by those apps and
 * theirs is read by this one. That only holds where the key name, the stored type and the meaning
 * all agree, which is a smaller set than the keys the three forks happen to share: `library_sorting_mode`
 * for instance is an Int here and an enum name there, and restoring one as the other is exactly
 * the corruption PreferencesHelper.migrateLibraryPreferences exists to clean up. Everything not on
 * [SHARED] goes to field 900 instead, which is ours.
 */
object BackupPreferencePolicy {
    /**
     * Verified against Mihon's LibraryPreferences, ReaderPreferences, DownloadPreferences,
     * UiPreferences and SourcePreferences: same key, same type, same meaning.
     */
    val SHARED =
        setOf(
            // Reader
            "pref_enable_transitions_key",
            "pref_show_page_number_key",
            "fullscreen",
            "cutout_short",
            "pref_keep_screen_on_key",
            "pref_custom_brightness_key",
            "custom_brightness_value",
            "pref_color_filter_key",
            "color_filter_value",
            "color_filter_mode",
            "pref_double_tap_anim_speed",
            "pref_image_scale_type_key",
            "pref_zoom_start_key",
            "pref_reader_theme_key",
            "always_show_chapter_transition",
            "crop_borders",
            "crop_borders_webtoon",
            "webtoon_side_padding",
            "reader_long_tap",
            "reader_volume_keys",
            "reader_volume_keys_inverted",
            "skip_read",
            "skip_filtered",
            // Library
            "pref_library_columns_portrait_key",
            "pref_library_columns_landscape_key",
            "pref_library_update_interval_key",
            "library_update_restriction",
            "library_update_categories",
            "auto_update_metadata",
            "display_download_badge",
            "display_unread_badge",
            "default_category",
            // Downloads
            "pref_download_only_over_wifi_key",
            "pref_remove_after_marked_as_read_key",
            "remove_after_read_slots",
            "download_new",
            "download_new_categories",
            "disallow_non_ascii_filenames",
            // Browse and UI
            "source_languages",
            "hidden_catalogues",
            "pinned_catalogues",
            "extension_repos",
            "app_date_format"
        )

    /** Credentials and session cookies. Never written to a backup. */
    private val SECRETS =
        setOf(
            "lock_hash",
            "lock_salt",
            "eh_settingsKey",
            "eh_sessionCookie",
            "eh_hathPerksCookie",
            "eh_ts_aspNetCookie",
            "eh_ipb_member_id",
            "eh_ipb_pass_hash",
            "eh_igneous"
        )

    private val SECRET_PREFIXES =
        listOf(
            "pref_mangasync_username_",
            "pref_mangasync_password_",
            "track_token_"
        )

    /**
     * Settings that describe this install rather than the user's choices: counters, migration
     * marks, and storage paths, which are SAF uris another device holds no permission for.
     */
    private val DEVICE_STATE =
        setOf(
            "last_version_code",
            "eh_last_version_code",
            "last_catalogue_source",
            "last_used_category",
            "ext_updates_count",
            "last_ext_check",
            "trusted_signatures",
            "performed_url_migration",
            "pref_library_update_skip_migrated_key",
            "eh_showSettingsUploadWarning2",
            "backup_directory",
            "download_directory"
        )

    fun isSecret(key: String): Boolean = key in SECRETS || SECRET_PREFIXES.any { key.startsWith(it) }

    fun isBackedUp(key: String): Boolean = !isSecret(key) && key !in DEVICE_STATE

    fun isShared(key: String): Boolean = key in SHARED
}
