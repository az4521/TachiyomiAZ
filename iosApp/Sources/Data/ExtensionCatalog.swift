import Foundation
import TachiyomiKit

/// One installable extension, flattened out of a repository index.
///
/// The shared `NetworkExtensionStore.Extension` is the wire format; this is what the UI needs.
/// Kept separate so the wire model stays a faithful mirror of `index.proto` rather than growing
/// display concerns.
struct AvailableExtension: Identifiable, Hashable {
    let name: String
    let packageName: String
    let versionName: String
    let versionCode: Int64
    let extensionLib: String
    /// nil when the repository publishes only an APK, which this app cannot load.
    let jarURL: String?
    let iconURL: String
    let languages: [String]
    /// Only CONTENT_WARNING_NSFW. Kept separate from [hasMixedContent] on purpose -- see below.
    let isNsfw: Bool
    /// CONTENT_WARNING_MIXED: the source carries both kinds of content.
    let hasMixedContent: Bool
    let repositoryName: String

    var id: String { packageName }

    /// The host loads Mihon extension libraries 1.4 through 1.6 and refuses anything else, so
    /// offering the rest would only produce failures at install time.
    var isSupported: Bool {
        // No JAR means no way to load it here, whatever its library version says.
        guard jarURL?.isEmpty == false else { return false }
        guard let major = extensionLib.split(separator: ".").first,
              let minorText = extensionLib.split(separator: ".").dropFirst().first,
              major == "1", let minor = Int(minorText) else { return false }
        return (4...6).contains(minor)
    }

    /// Tachiyomi uses "all" for extensions that serve every language.
    var isMultiLanguage: Bool { languages.contains("all") }

    var displayLanguages: String {
        if isMultiLanguage { return "All languages" }
        return languages
            .compactMap { Locale.current.localizedString(forLanguageCode: $0) ?? $0 }
            .joined(separator: ", ")
    }
}

extension AvailableExtension {
    init(entry: NetworkExtensionStore.Extension, repositoryName: String) {
        // Extensions are published as "Tachiyomi: Name"; the prefix is noise in a list of them.
        self.name = entry.name.replacingOccurrences(of: "Tachiyomi: ", with: "")
        self.packageName = entry.packageName
        self.versionName = entry.versionName
        self.versionCode = entry.versionCode
        self.extensionLib = entry.extensionLib
        // resources.jarUrl, never apkUrl. The APK is an Android package with a binary manifest;
        // the host wants the JAR, and rejects an APK with "AndroidManifest.xml is not the textual
        // manifest used by tachiyomix jar artifacts".
        self.jarURL = entry.resources.jarUrl
        self.iconURL = entry.resources.iconUrl
        self.languages = Array(Set(entry.sources.map { $0.language })).sorted()
        // MIXED is deliberately *not* folded into NSFW here. :app's toAvailableExtensions uses
        // contentWarning >= MIXED, which is fine for a flag it merely displays -- but this drives
        // a filter that defaults to hiding, and MangaDex and Weeb Central are both MIXED. Treating
        // them as adult-only silently removes the two largest sources in the repository.
        self.isNsfw = entry.contentWarning == .nsfw
        self.hasMixedContent = entry.contentWarning == .mixed
        self.repositoryName = repositoryName
    }
}

/// An extension that has been downloaded and validated by the host.
struct InstalledExtension: Codable, Identifiable, Hashable {
    let packageName: String
    let name: String
    let versionName: String
    let versionCode: Int64
    let entryClass: String
    let jarPath: String

    var id: String { packageName }
}

/// Downloads extension JARs and has the JVM validate them.
///
/// Installing is deliberately two steps. Downloading is just a file; an extension is only
/// "installed" once `inspectExtension` has opened the JAR inside the VM and agreed it is a
/// supported Mihon extension. That keeps a truncated download or an APK-in-disguise from sitting
/// in the list looking healthy.
@MainActor
final class ExtensionCatalog: ObservableObject {
    @Published private(set) var installed: [InstalledExtension] = []
    @Published private(set) var installing: Set<String> = []
    @Published private(set) var lastError: String?

    private let key = "extensions.installed"
    private unowned let jvm: JVMHost

    init(jvm: JVMHost) {
        self.jvm = jvm
        if let data = UserDefaults.standard.data(forKey: key),
           let stored = try? JSONDecoder().decode([InstalledExtension].self, from: data) {
            installed = stored
        }
    }

    func isInstalled(_ extensionItem: AvailableExtension) -> Bool {
        installed.contains { $0.packageName == extensionItem.packageName }
    }

    func installedVersion(of extensionItem: AvailableExtension) -> Int64? {
        installed.first { $0.packageName == extensionItem.packageName }?.versionCode
    }

    private static func extensionsDirectory() throws -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("Extensions", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    func install(_ extensionItem: AvailableExtension) async {
        guard !installing.contains(extensionItem.packageName) else { return }
        installing.insert(extensionItem.packageName)
        lastError = nil
        defer { installing.remove(extensionItem.packageName) }

        guard let jarURL = extensionItem.jarURL, let url = URL(string: jarURL) else {
            lastError = "\(extensionItem.name): this repository publishes no JAR for it, only an APK."
            return
        }

        do {
            let (temporaryURL, response) = try await URLSession.shared.download(from: url)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                lastError = "\(extensionItem.name): download failed with HTTP \(http.statusCode)."
                return
            }

            let destination = try Self.extensionsDirectory()
                .appendingPathComponent("\(extensionItem.packageName).jar")
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: temporaryURL, to: destination)

            // The VM is the authority on whether this is loadable, not the index metadata.
            let inspection = try await jvm.inspect(jarPath: destination.path)
            guard inspection.success, let entryClass = inspection.result, !entryClass.isEmpty else {
                try? FileManager.default.removeItem(at: destination)
                lastError = "\(extensionItem.name): \(inspection.error ?? "the host rejected this JAR.")"
                return
            }

            installed.removeAll { $0.packageName == extensionItem.packageName }
            installed.append(
                InstalledExtension(
                    packageName: extensionItem.packageName,
                    name: extensionItem.name,
                    versionName: extensionItem.versionName,
                    versionCode: extensionItem.versionCode,
                    entryClass: entryClass,
                    jarPath: destination.path
                )
            )
            installed.sort { $0.name < $1.name }
            persist()
        } catch {
            lastError = "\(extensionItem.name): \(error.localizedDescription)"
        }
    }

    func uninstall(_ item: InstalledExtension) {
        try? FileManager.default.removeItem(atPath: item.jarPath)
        installed.removeAll { $0.packageName == item.packageName }
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(installed) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
