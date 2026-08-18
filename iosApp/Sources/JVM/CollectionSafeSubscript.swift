import Foundation

extension Collection {
    /// Bounds-checked subscript, used by the vendored `JVMWebKitBridge`.
    ///
    /// The bridge parses command payloads positionally and indexes past the end whenever the JVM
    /// sends fewer arguments than an operation allows, so this is what keeps a malformed command
    /// from trapping the whole app.
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
