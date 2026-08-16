package eu.kanade.tachiyomi.source.model

/**
 * `android.net.Uri` on Android, and an inert placeholder everywhere else.
 *
 * [Page.uri] is only meaningful on Android, where it points at a downloaded file that
 * DownloadPageLoader opens, and it is part of the extension API's ABI. Aliasing keeps the emitted
 * Android bytecode as `android.net.Uri`, while iOS gets a type nothing ever constructs.
 *
 * abstract, and redeclaring toString, because android.net.Uri is abstract and declares toString
 * abstract itself -- an actual typealias has to match modality exactly. equals and hashCode are
 * concrete on Uri, so they are left inherited.
 */
expect abstract class PlatformUri {
    abstract override fun toString(): String
}
