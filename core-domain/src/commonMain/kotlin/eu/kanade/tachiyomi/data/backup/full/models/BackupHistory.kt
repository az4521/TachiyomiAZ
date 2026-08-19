package eu.kanade.tachiyomi.data.backup.full.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@ExperimentalSerializationApi
@Serializable
data class BackupHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastRead: Long,
    /**
     * Milliseconds spent reading this chapter.
     *
     * Mihon records it and neither this app nor the Android one has ever read it, so it was
     * absent from this model and dropped on import. Declared so it survives a round trip: the iOS
     * app accumulates the same figure per chapter, and without a field for it a backup taken there
     * and restored anywhere else loses it silently. Defaults to zero, which is omitted from the
     * bytes, so nothing that does not set it changes.
     */
    @ProtoNumber(3) var readDuration: Long = 0
)

@Serializable
data class BrokenBackupHistory(
    @ProtoNumber(0) var url: String,
    @ProtoNumber(1) var lastRead: Long
) {
    fun toBackupHistory() = BackupHistory(url, lastRead)
}
