import Foundation

/// The library screen's model.
///
/// Upstream's version is built on raw `NSManagedObjectContext` work, so it is being rewritten over
/// the shared queries rather than vendored. This starts with the parts that are pure UI vocabulary
/// -- the sort, filter and pin choices the settings pages offer -- which carry no persistence and
/// so port unchanged. The querying half arrives with the library screen.
enum LibraryViewModel {
    /// Which entries get pinned to the top of the library.
    enum PinType: String, CaseIterable {
        case none
        case unread
        case updatedChapters

        var title: String {
            switch self {
            case .none: NSLocalizedString("PIN_DISABLED")
            case .unread: NSLocalizedString("PIN_UNREAD")
            case .updatedChapters: NSLocalizedString("PIN_UPDATED_CHAPTERS")
            }
        }

        var needsUpdateOnContentOpen: Bool {
            switch self {
            case .none, .unread: false
            case .updatedChapters: true
            }
        }
    }
}
