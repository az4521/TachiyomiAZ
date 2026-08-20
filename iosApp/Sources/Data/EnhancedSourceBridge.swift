import ExtensionRunner
import Foundation

/// Connects the enhanced trackers to their JVM extensions.
///
/// Komga, Kavita and Suwayomi track against the same server the manga came from, so their trackers
/// need that server's address and credentials. Upstream reads them from `UserDefaults` under
/// `<sourceKey>.server` and `<sourceKey>.login.*`, which is where its own built-in sources write
/// their settings.
///
/// Here those sources are JVM extensions installed from a repository, and their settings live in
/// the extension's preferences inside the VM. This mirrors them out to the keys the helpers read,
/// so `KomgaHelper` and friends work unmodified against a JVM-backed server.
///
/// Mirrored rather than read through on demand because the helpers are synchronous and the VM is
/// not; the values change only when the user edits the source's settings.
enum EnhancedSourceBridge {
    /// Extension package names for the three enhanced services, as published in the repositories.
    private static let packages: [String: String] = [
        "eu.kanade.tachiyomi.extension.all.komga": "komga",
        "eu.kanade.tachiyomi.extension.all.kavita": "kavita",
        "eu.kanade.tachiyomi.extension.all.suwayomi": "suwayomi"
    ]

    /// Which enhanced service an extension provides, if any.
    static func service(forExtension packageName: String) -> String? {
        packages[packageName]
    }

    /// Which enhanced service a source belongs to, if any.
    ///
    /// This is what the three trackers ask before offering to track a title. Upstream answers it
    /// with `sourceKey.hasPrefix(KomgaSourceRunner.sourceKeyPrefix)`, because its Komga source is
    /// built in and owns a key prefix. Here the source is a JVM extension whose key is
    /// `mihon.<id>` like every other, carrying nothing about which server it talks to -- so the
    /// question is answered by the extension behind it instead.
    static func service(forSourceKey sourceKey: String) -> String? {
        guard
            let id = SourceIdentity.numericId(sourceKey),
            let descriptor = SourceManager.shared.descriptors.first(where: { $0.id == id })
        else { return nil }
        return service(forExtension: descriptor.extensionId)
    }

    /// The setting keys the Tachiyomi extensions use, mapped to the names the helpers read.
    ///
    /// The extensions spell these differently from Aidoku's sources -- `address` rather than
    /// `server`, and credentials without the `login.` prefix -- so the mapping is explicit.
    private static let settingKeys: [String: String] = [
        "address": "server",
        "baseurl": "server",
        "username": "login.username",
        "password": "login.password",
        "apikey": "token",
        "api_key": "token"
    ]

    /// Reads each enhanced source's settings out of the VM and writes them where the trackers look.
    ///
    /// Called after sources load and after a source's settings change.
    static func mirrorSettings(for sources: [SourceDescriptor]) async {
        for descriptor in sources {
            guard service(forExtension: descriptor.extensionId) != nil else { continue }

            let settings: [TachiyomiXSettingDescriptor]
            do {
                settings = try await JVMSourceRuntime.shared.settings(
                    extensionId: descriptor.extensionId,
                    sourceId: descriptor.id
                )
            } catch {
                LogManager.logger.error(
                    "Could not read settings for \(descriptor.name): \(error.localizedDescription)"
                )
                continue
            }

            let sourceKey = SourceIdentity.key(for: descriptor.id)
            for setting in settings {
                guard
                    let mapped = settingKeys[setting.key.lowercased()],
                    let value = setting.currentValue,
                    !value.isEmpty
                else { continue }
                UserDefaults.standard.set(value, forKey: "\(sourceKey).\(mapped)")
            }
        }
    }

    /// Sources belonging to one enhanced service, for the tracking settings screen.
    static func sources(for service: String, in sources: [ExtensionRunner.Source]) -> [ExtensionRunner.Source] {
        let keys = Set(
            SourceManager.shared.descriptors
                .filter { Self.service(forExtension: $0.extensionId) == service }
                .map { SourceIdentity.key(for: $0.id) }
        )
        return sources.filter { keys.contains($0.key) }
    }
}
