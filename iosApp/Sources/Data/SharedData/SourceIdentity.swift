import Foundation

/// Translates between the two ways a source is named.
///
/// The vendored UI addresses everything by a string `sourceId`, because Aidoku sources are named by
/// reverse-DNS key. The shared database uses Android's numeric `source` column. JVM sources carry
/// the Android id, and `TachiyomiXSourceRunner.key(for:)` renders it as `mihon.<id>`, so the two
/// forms convert losslessly -- this is the one place that knows how.
enum SourceIdentity {
    static let prefix = "mihon."

    static func key(for id: Int64) -> String { "\(prefix)\(id)" }

    /// Accepts either form: a `mihon.`-prefixed key, or a bare numeric id as a string.
    static func numericId(_ sourceId: String) -> Int64? {
        if sourceId.hasPrefix(prefix) {
            return Int64(sourceId.dropFirst(prefix.count))
        }
        return Int64(sourceId)
    }
}
