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
@MainActor
final class JVMHost: ObservableObject {
    enum State: Equatable {
        case notStarted
        case starting
        case running(javaVersion: String, runtime: String)
        case failed(String)
    }

    @Published private(set) var state: State = .notStarted

    private var runtime: JVMRuntime?

    /// Boots the VM. Slow enough to be worth doing off the main actor -- the Zero interpreter has
    /// to map the module image and initialise the boot classpath before it will answer anything.
    func start() async {
        guard case .notStarted = state else { return }
        state = .starting

        do {
            // The bridge asks for InitialCodeCacheSize=4m, which the simulator refuses. Zero is
            // an interpreter and compiles nothing, so the code cache only ever holds stubs and
            // adapters -- shrinking it costs nothing and is appended after the bridge's defaults,
            // so these win.
            // NOTE: the VM does not currently start on the iOS Simulator. It dies in
            // initialisation with:
            //
            //     Could not reserve enough space in CodeCache (NK)
            //
            // where N is always exactly InitialCodeCacheSize. Ruled out by experiment: capacity
            // (256k fails as readily as 8m), segmentation (-XX:-SegmentedCodeCache changes
            // nothing), and the JIT entitlement (verified present via codesign -d --entitlements,
            // still fails). The reservation of executable pages is being refused outright rather
            // than being too small.
            //
            // Left on the bridge's own defaults deliberately -- overriding them only moved the
            // number in the error. See IOS_PORT.md for what to try next.
            let configuration = try JVMRuntimeConfiguration.bundled()
            let runtime = try await Task.detached(priority: .userInitiated) {
                try JVMRuntime(configuration: configuration)
            }.value
            self.runtime = runtime

            let response = try await ping()
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

    /// The cheapest possible round trip: proves the VM started, the host JAR is on the classpath,
    /// and JSON survives both directions.
    func ping() async throws -> ExtensionHostResponse {
        guard let runtime else {
            throw JVMRuntimeError.invalidConfiguration("The JVM has not been started.")
        }
        let request = ExtensionHostRequest(operation: "ping")
        return try await Task.detached(priority: .userInitiated) {
            try runtime.dispatch(request) as ExtensionHostResponse
        }.value
    }
}
