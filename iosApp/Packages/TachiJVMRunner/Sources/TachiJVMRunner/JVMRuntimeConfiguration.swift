@preconcurrency import Foundation

public struct JVMRuntimeConfiguration: Sendable {
    public let javaHomeURL: URL
    public let frameworksURL: URL
    public let classpathURLs: [URL]
    public let additionalOptions: [String]

    public init(
        javaHomeURL: URL,
        frameworksURL: URL,
        classpathURLs: [URL],
        additionalOptions: [String] = []
    ) {
        self.javaHomeURL = javaHomeURL
        self.frameworksURL = frameworksURL
        self.classpathURLs = classpathURLs
        self.additionalOptions = additionalOptions
    }

    var classpath: String {
        classpathURLs
            .map(\.path)
            .joined(separator: ":")
    }

    public static func bundled(
        in bundle: Bundle = .main,
        additionalOptions: [String] = []
    ) throws -> JVMRuntimeConfiguration {
        guard let resourcesURL = bundle.resourceURL else {
            throw JVMRuntimeError.invalidConfiguration(
                "Application resource directory is unavailable"
            )
        }
        let javaHomeURL = resourcesURL.appendingPathComponent(
            "java_bundle",
            isDirectory: true
        )
        let hostURL = resourcesURL.appendingPathComponent(
            "tachiaz-extension-host.jar"
        )
        let mobileShimsURL = resourcesURL.appendingPathComponent(
            "tachiaz-mobile-shims.jar"
        )
        let compatibilityURL = resourcesURL.appendingPathComponent(
            "tachiaz-compat",
            isDirectory: true
        )
        let fileManager = FileManager.default
        var javaHomeIsDirectory: ObjCBool = false
        guard
            fileManager.fileExists(
                atPath: javaHomeURL.path,
                isDirectory: &javaHomeIsDirectory
            ),
            javaHomeIsDirectory.boolValue
        else {
            throw JVMRuntimeError.invalidConfiguration(
                "Bundled java_bundle is missing"
            )
        }

        // HotSpot may terminate the process during JNI_CreateJavaVM when its
        // boot image is absent. Validate the complete minimal image before
        // crossing into native startup so a damaged bundle produces a normal
        // recoverable configuration error instead.
        let requiredRuntimeFiles = [
            "lib/modules",
            "conf/net.properties",
            "conf/security/java.security",
            "lib/security/blocked.certs",
            "lib/security/public_suffix_list.dat",
            "lib/tzdb.dat",
            "lib/security/cacerts",
        ]
        for relativePath in requiredRuntimeFiles {
            let fileURL = javaHomeURL.appendingPathComponent(relativePath)
            var isDirectory: ObjCBool = false
            guard
                fileManager.fileExists(
                    atPath: fileURL.path,
                    isDirectory: &isDirectory
                ),
                !isDirectory.boolValue,
                fileManager.isReadableFile(atPath: fileURL.path)
            else {
                throw JVMRuntimeError.invalidConfiguration(
                    "Bundled JVM runtime file is missing or unreadable: \(relativePath)"
                )
            }
        }
        guard fileManager.fileExists(atPath: hostURL.path) else {
            throw JVMRuntimeError.invalidConfiguration(
                "Bundled extension host JAR is missing"
            )
        }
        guard fileManager.fileExists(atPath: mobileShimsURL.path) else {
            throw JVMRuntimeError.invalidConfiguration(
                "Bundled mobile JVM shims are missing"
            )
        }
        #if os(iOS)
        // The mobile VM is statically linked. An app containing no dynamic
        // frameworks may not have a physical Frameworks directory, but the
        // JNI bridge still accepts this path for java.library.path.
        let frameworksURL = bundle.privateFrameworksURL ??
            bundle.bundleURL.appendingPathComponent(
                "Frameworks",
                isDirectory: true
            )
        #else
        guard let frameworksURL = bundle.privateFrameworksURL else {
            throw JVMRuntimeError.invalidConfiguration(
                "Application framework directory is unavailable"
            )
        }
        #endif

        let compatibilityJars = try fileManager
            .contentsOfDirectory(
                at: compatibilityURL,
                includingPropertiesForKeys: nil
            )
            .filter {
                $0.pathExtension == "jar" &&
                    !$0.lastPathComponent.hasPrefix("logback-")
            }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard !compatibilityJars.isEmpty else {
            throw JVMRuntimeError.invalidConfiguration(
                "Bundled Suwayomi compatibility JARs are missing"
            )
        }
        let applicationSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let jvmHome = applicationSupport.appendingPathComponent(
            "JVMHome",
            isDirectory: true
        )
        try fileManager.createDirectory(
            at: jvmHome,
            withIntermediateDirectories: true
        )

        return JVMRuntimeConfiguration(
            javaHomeURL: javaHomeURL,
            frameworksURL: frameworksURL,
            // Keep the complete AndroidCompat/Mihon API ahead of the small
            // host fixture surface. The iOS-only SystemClock replacement is
            // loaded from the boot shim before either classpath entry.
            classpathURLs: compatibilityJars + [hostURL],
            additionalOptions: [
                "-Xbootclasspath/a:\(mobileShimsURL.path)",
                "-Duser.home=\(jvmHome.path)",
                "-Duser.dir=\(jvmHome.path)",
                "-Djava.io.tmpdir=\(FileManager.default.temporaryDirectory.path)",
                "-XX:ErrorFile=\(jvmHome.appendingPathComponent("hs_err_pid%p.log").path)"
            ] + additionalOptions
        )
    }
}
