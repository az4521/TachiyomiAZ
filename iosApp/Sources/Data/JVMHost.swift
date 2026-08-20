import Foundation
import TachiJVMRunner

/// The embedded JVM, and the extension host running inside it.
///
/// iOS forbids JIT, so this is OpenJDK Mobile's **Zero** interpreter — statically linked, started
/// once per process. Extensions are JARs executed in-process, which means they share the app's
/// permissions and its crash boundary; that is a deliberate tradeoff of the sideloading model, not
/// an oversight.
///
/// Everything crosses the boundary as JSON through `dispatch`. That keeps the Swift side free of
/// JNI and means the shared Kotlin layer can eventually sit in front of this with a plain
/// interface, the same way it sits in front of the database.
///
/// This class does **not** own the VM. `JVMSourceRuntime` does, and starts it lazily on first use.
/// JNI permits exactly one VM per process, so a second `JVMRuntime(configuration:)` here would fail
/// with JNI_EEXIST -- and the vendored UI reaches the VM through `TachiyomiXSourceRunner`, which
/// routes to `JVMSourceRuntime.shared`. This is the observable status wrapper the app's own screens
/// bind to; starting it means asking that actor to come up.
@MainActor
final class JVMHost: ObservableObject {
    enum State: Equatable {
        case notStarted
        case starting
        case running(javaVersion: String, runtime: String)
        case failed(String)
    }

    @Published private(set) var state: State = .notStarted

    /// Kept for the screens that check whether the host is up. Requests go through
    /// `JVMSourceRuntime`, which owns the VM.
    var runtime: JVMSourceRuntime? { isRunning ? JVMSourceRuntime.shared : nil }

    var isRunning: Bool {
        if case .running = state { return true }
        return false
    }

    /// Boots the VM. Slow enough to be worth doing off the main actor -- the Zero interpreter has
    /// to map the module image and initialise the boot classpath before it will answer anything.
    func start() async {
        guard case .notStarted = state else { return }
        state = .starting

        do {
            // Starting the VM is JVMSourceRuntime's job; pinging it is what brings it up. The
            // simulator notes that used to live here have moved to IOS_PORT.md.
            let response = try await JVMSourceRuntime.shared.ping()
            if response.success {
                state = .running(
                    javaVersion: response.javaVersion ?? "unknown",
                    runtime: response.runtime ?? "unknown"
                )
            } else {
                state = .failed(response.error ?? "The extension host rejected the ping.")
            }
        } catch {
            state = .failed(String(describing: error))
        }
    }

    /// Clears a failure so [start] will run again. A boot failure is usually a missing or
    /// mis-staged resource, which is fixable without relaunching the app.
    func retry() async {
        state = .notStarted
        await start()
    }

    /// Asks the host to open a JAR and report its metadata. This is what decides whether a
    /// downloaded file is really a loadable Mihon extension -- the index only claims it is.
    func inspect(jarPath: String) async throws -> JVMExtensionInspection {
        try await JVMSourceRuntime.shared.inspect(jar: URL(fileURLWithPath: jarPath))
    }

    /// The cheapest possible round trip: proves the VM started, the host JAR is on the classpath,
    /// and JSON survives both directions.
    func ping() async throws -> ExtensionHostResponse {
        try await JVMSourceRuntime.shared.ping()
    }
}
