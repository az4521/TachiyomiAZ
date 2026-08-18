import Foundation
import os

/// Minimal logger, provided so the vendored `JVMWebKitBridge` compiles unchanged.
///
/// The bridge came from tachiyomiazios and calls `LogManager.logger.error(...)` with an ordinary
/// interpolated `String`. `os.Logger` takes an `OSLogMessage`, so this wraps one and accepts a
/// plain String instead -- keeping the bridge byte-identical to its source, which matters for a
/// 968-line file that will want re-syncing later.
enum LogManager {
    struct Log {
        private let logger = Logger(subsystem: "eu.kanade.tachiyomi.az.ios", category: "runtime")

        func error(_ message: String) { logger.error("\(message, privacy: .public)") }
        func warning(_ message: String) { logger.warning("\(message, privacy: .public)") }
        func info(_ message: String) { logger.info("\(message, privacy: .public)") }
        func debug(_ message: String) { logger.debug("\(message, privacy: .public)") }
    }

    static let logger = Log()
}
