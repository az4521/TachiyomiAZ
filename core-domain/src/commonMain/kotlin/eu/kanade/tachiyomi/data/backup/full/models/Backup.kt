package eu.kanade.tachiyomi.data.backup.full.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Backup json model
 */
@ExperimentalSerializationApi
@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga>,
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    // Bump by 100 to specify this is a 0.x value
    @ProtoNumber(100) var backupBrokenSources: List<BrokenBackupSource> = emptyList(),
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    /**
     * App settings, in the field Mihon and TachiyomiSY use for them, holding only the keys all
     * three read the same way. See [BackupPreferencePolicy].
     */
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    // SY specific values
    @ProtoNumber(600) var backupSavedSearches: List<BackupSavedSearch> = emptyList(),
    /**
     * Settings only this app has, and settings whose key another fork also uses but reads
     * differently. Kept clear of 104-106 (Mihon), 501 (below) and 600 (SY) so no fork mistakes
     * one app's value for another's.
     */
    @ProtoNumber(900) var backupAzPreferences: List<BackupPreference> = emptyList(),
    /**
     * State the iOS app keeps that has no field in this format -- reading sessions, per-title
     * settings, source lists -- serialized as JSON.
     *
     * Declared here rather than left as an undeclared field appended by that app, so both sides
     * know it exists: Android preserves it across a decode and re-encode instead of dropping it,
     * and it is documented rather than discovered. Null on anything Android writes, and omitted
     * from the bytes when null, so a backup produced there is byte-identical to before.
     */
    @ProtoNumber(501) var iosState: ByteArray? = null
)
