import UIKit

/// The placeholder art the vendored UI falls back to when a cover or page is missing.
///
/// Upstream gets these from Xcode's generated asset symbols. Those are declared `@available(iOS 17)`
/// because they call `UIImage(resource:)`, and this app targets iOS 15, so the assets are looked up
/// by name instead -- same catalog entries, no availability floor.
extension UIImage {
    static var mangaPlaceholder: UIImage {
        UIImage(named: "MangaPlaceholder") ?? UIImage()
    }

    static var bannerPlaceholder: UIImage {
        UIImage(named: "BannerPlaceholder") ?? UIImage()
    }
}
