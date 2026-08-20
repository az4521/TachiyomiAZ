//
//  CoverStoreBridge.swift
//  TachiyomiAZ
//

import Foundation
import Nuke
import TachiyomiKit

/// The cover cache, as `:core-domain` sees it.
///
/// `removeCovers` is shared: dropping a title's cached cover so it is fetched again is the same
/// decision on both apps, and it is what makes removing a manga from the library reclaim its cover
/// rather than leaving it on disk forever.
///
/// The two halves differ in how much of them is portable. Eviction is: the cover url is the cache
/// key, and Nuke's `DataCache` removes by key the way Android's file cache removes by filename.
/// Recognising a *custom* cover is not, because the two apps represent one differently -- Android
/// writes the image into its cover cache under a name derived from the manga and leaves
/// `thumbnail_url` pointing at the source, while this app writes it to `Documents/Covers` and puts
/// an `aidoku-image://` url in the column. So that half is answered here, from the scheme.
final class CoverStoreBridge: NSObject, CoverStore {
    static let shared = CoverStoreBridge()

    /// The scheme this app gives a cover the user chose themselves.
    private static let customScheme = "aidoku-image://"

    func hasCustomCover(manga: any DbManga) -> Bool {
        manga.thumbnail_url?.hasPrefix(Self.customScheme) == true
    }

    /// Drops the cached cover, and optionally the custom one behind it.
    ///
    /// - Returns: how many were removed, which the shared rule does not read but Android's
    ///   implementation reports, so this reports it too.
    func deleteFromCache(manga: any DbManga, deleteCustomCover: Bool) -> Int32 {
        guard let url = manga.thumbnail_url, !url.isEmpty else { return 0 }

        var deleted: Int32 = 0

        // A custom cover has no cache entry -- it was never fetched -- so removing it means
        // removing the file this app wrote.
        if hasCustomCover(manga: manga) {
            if deleteCustomCover, removeCustomCoverFile(url: url) {
                deleted += 1
            }
            return deleted
        }

        if let remote = URL(string: url) {
            ImagePipeline.shared.cache.removeCachedImage(for: ImageRequest(url: remote))
            deleted += 1
        }
        return deleted
    }

    /// Removes the file an `aidoku-image://` url points at.
    ///
    /// The url is relative to the documents directory, so only its path is used -- a url naming
    /// somewhere else is ignored rather than followed.
    private func removeCustomCoverFile(url: String) -> Bool {
        let path = String(url.dropFirst(Self.customScheme.count))
        guard !path.isEmpty, !path.contains("..") else { return false }

        let file = FileManager.default.documentDirectory
            .appendingPathComponent(path.hasPrefix("/") ? String(path.dropFirst()) : path)
        guard FileManager.default.fileExists(atPath: file.path) else { return false }
        return (try? FileManager.default.removeItem(at: file)) != nil
    }
}
