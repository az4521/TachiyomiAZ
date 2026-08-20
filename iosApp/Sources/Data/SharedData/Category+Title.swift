import Foundation
import TachiyomiKit

/// Upstream's `CategoryObject` exposes the category's text as `title`; the shared model, following
/// Android, calls it `name`. Bridging it here lets the vendored views keep their own spelling
/// without a second field to keep in step.
extension TachiyomiKit.Category {
    var title: String? { name }
}
