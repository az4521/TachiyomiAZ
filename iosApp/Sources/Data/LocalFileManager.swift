import Foundation
import ZIPFoundation
import ExtensionRunner

/// The parts of upstream's `LocalFileManager` the download subsystem needs.
///
/// Upstream's full version also imports CBZ/EPUB files as a local *source*, which is a feature of
/// its own resting on a CoreData index -- see Vendored/_excluded/Local. Downloads need none of that:
/// they need to know which file extensions count as pages, and how to read the pages back out of a
/// downloaded `.cbz`. Both are lifted verbatim so the excluded file can replace this wholesale when
/// local sources are ported.
final class LocalFileManager {
    static let shared = LocalFileManager()

    private init() {}

    static let allowedFileExtensions = Set(["cbz", "zip", "epub"])
    static let allowedImageExtensions = Set(["jpg", "jpeg", "png", "webp", "gif", "heic", "avif"])
    static let allowedTextExtensions = Set(["txt", "md"])
    static let allowedPageExtensions = allowedImageExtensions.union(allowedTextExtensions)

    nonisolated func readPages(from archiveURL: URL) -> [ExtensionRunner.Page] {
        let archive: Archive
        do {
            archive = try Archive(url: archiveURL, accessMode: .read)
        } catch {
            LogManager.logger.error("Failed to read archive: \(error)")
            return []
        }

        var descriptionFiles: [Entry] = []

        var pages = archive
            .filter { entry in
                // ignore hidden files
                let lastPathComponent = entry.path.lastPathComponent()
                guard !lastPathComponent.hasPrefix(".") else {
                    return false
                }
                // ensure extension is allowed
                let ext = entry.path.pathExtension().lowercased()
                if ext == "txt" {
                    if entry.path.hasSuffix("desc.txt") {
                        descriptionFiles.append(entry)
                        return false
                    }
                    return true
                }
                return Self.allowedPageExtensions.contains(ext)
            }
            // sort by file name
            .sorted {
                $0.path.localizedStandardCompare($1.path) == .orderedAscending
            }
            .map { entry in
                ExtensionRunner.Page(content: .zipFile(url: archiveURL, filePath: entry.path))
            }

        for entry in descriptionFiles {
            guard
                let index = entry.path
                    .lastPathComponent()
                    .split(separator: ".", maxSplits: 1)
                    .first
                    .flatMap({ Int($0) }),
                index > 0,
                index <= pages.count
            else { break }

            do {
                var descriptionData = Data()
                _ = try archive.extract(
                    entry,
                    consumer: { data in
                        descriptionData.append(data)
                    }
                )
                pages[index - 1].hasDescription = true
                pages[index - 1].description = String(data: descriptionData, encoding: .utf8)
            } catch {
                LogManager.logger.error("Failed to extract page description text from archive: \(error)")
                continue
            }
        }

        return pages
    }
}
