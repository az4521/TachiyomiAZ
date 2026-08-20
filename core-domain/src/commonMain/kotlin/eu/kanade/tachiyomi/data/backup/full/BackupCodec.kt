package eu.kanade.tachiyomi.data.backup.full

import eu.kanade.tachiyomi.data.backup.full.models.Backup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Reads and writes the `.tachibk` protobuf payload.
 *
 * The models already carried their `@ProtoNumber`s and the Android app already encoded through
 * them; what was missing was a way for iOS to do the same. `Backup.serializer()` and ProtoBuf's
 * reified helpers do not survive export to Swift, so this is the non-generic surface they cross
 * on: two functions taking and returning bytes.
 *
 * It exists to delete a hand-written protobuf reader and writer on the iOS side. Both apps parsing
 * the same bytes through the same field numbers is the entire point of sharing the format, and a
 * second implementation of it -- however carefully transcribed -- is a place for the two to
 * disagree about what a backup means.
 *
 * Gzip is deliberately not here. Android has okio and iOS has its own; wrapping bytes is the one
 * part of this that is genuinely platform work.
 */
@OptIn(ExperimentalSerializationApi::class)
object BackupCodec {
    /**
     * Lenient on purpose. A backup written by a newer version, by Mihon, or by the iOS app -- which
     * appends its own state in a field this model does not declare -- must still restore rather
     * than fail whole. `BackupFormatTest` pins that behaviour.
     */
    private val protoBuf = ProtoBuf {
        encodeDefaults = false
    }

    fun encode(backup: Backup): ByteArray = protoBuf.encodeToByteArray(backup)

    fun decode(bytes: ByteArray): Backup = protoBuf.decodeFromByteArray(bytes)
}
