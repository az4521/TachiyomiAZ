import Foundation
import TachiJVMRunner

struct JVMExtensionManifest: Codable, Hashable, Sendable {
    let id: String
    let name: String
    let version: String
    let entryClass: String
    let sourceURL: URL?
    let iconURL: URL?
    let sha256: String
    let versionCode: String?
    let extensionLibrary: String?
    let isNsfw: Bool?

    init(
        inspection: JVMExtensionInspection,
        sourceURL: URL?,
        iconURL: URL? = nil,
        sha256: String,
        versionCode: String? = nil,
        extensionLibrary: String? = nil,
        isNsfw: Bool? = nil
    ) {
        id = inspection.packageName
        name = inspection.name
        version = inspection.version
        entryClass = inspection.entryClass
        self.sourceURL = sourceURL
        self.iconURL = iconURL
        self.sha256 = sha256
        self.versionCode = versionCode ?? inspection.versionCode
        self.extensionLibrary =
            extensionLibrary ?? inspection.extensionLibrary
        self.isNsfw = isNsfw
    }

    var directoryName: String {
        id
            .unicodeScalars
            .map { scalar in
                CharacterSet.alphanumerics.contains(scalar)
                    ? String(scalar)
                    : "_"
            }
            .joined()
    }

    var versionDirectoryName: String {
        let encoded = version.unicodeScalars.map { scalar in
            CharacterSet.alphanumerics.contains(scalar) ||
                scalar == "." ||
                scalar == "-" ||
                scalar == "_"
                ? String(scalar)
                : "_\(String(scalar.value, radix: 16))_"
        }
        .joined()
        return encoded.isEmpty ? "_" : encoded
    }
}

struct JVMExtensionInspection: Hashable, Sendable {
    let packageName: String
    let name: String
    let version: String
    let versionCode: String
    let entryClass: String
    let extensionLibrary: String?
    let maximumClassVersion: Int
    let requiredJavaVersion: Int
    let runtimeCompatible: Bool

    init(response: ExtensionHostResponse) throws {
        guard
            response.success,
            let packageName = response.packageName,
            let name = response.name,
            let version = response.version,
            let versionCode = response.versionCode,
            let entryClass = response.entryClass,
            let maximumClassVersionText = response.maximumClassVersion,
            let maximumClassVersion = Int(maximumClassVersionText),
            let requiredJavaVersionText = response.requiredJavaVersion,
            let requiredJavaVersion = Int(requiredJavaVersionText)
        else {
            throw JVMSourceRuntime.RuntimeError.hostRejected(
                response.error ?? "The JAR metadata is incomplete."
            )
        }

        self.packageName = packageName
        self.name = name
        self.version = version
        self.versionCode = versionCode
        self.entryClass = entryClass
        self.extensionLibrary = response.extensionLibrary
        self.maximumClassVersion = maximumClassVersion
        self.requiredJavaVersion = requiredJavaVersion
        runtimeCompatible = response.runtimeCompatible == "true"
    }
}
