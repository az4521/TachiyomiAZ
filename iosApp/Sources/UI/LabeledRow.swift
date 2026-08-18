import SwiftUI

/// A label with a trailing detail value.
///
/// Stands in for `LabeledContent`, which is iOS 16. This project targets iOS 15 because the JVM
/// runtime is built against an iOS 15 deployment target and sideloaded builds run on old devices.
struct LabeledRow: View {
    let label: String
    let value: String

    init(_ label: String, value: String) {
        self.label = label
        self.value = value
    }

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
    }
}
