import SwiftUI

/// A cover, or a deterministic placeholder when there is no usable URL.
///
/// The placeholder is derived from the title rather than being a grey box, so a source with no
/// thumbnails still produces a readable grid instead of looking broken.
struct MangaCoverImage: View {
    let url: String?
    let title: String

    var body: some View {
        if let url, let parsed = URL(string: url), !url.isEmpty {
            AsyncImage(url: parsed) { phase in
                switch phase {
                case let .success(image):
                    image.resizable().scaledToFill()
                case .failure:
                    placeholder
                case .empty:
                    ZStack {
                        placeholder
                        ProgressView().controlSize(.small)
                    }
                @unknown default:
                    placeholder
                }
            }
        } else {
            placeholder
        }
    }

    private var placeholder: some View {
        LinearGradient(
            colors: colors,
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .overlay {
            Text(initials)
                .font(.title2.weight(.bold))
                .foregroundStyle(.white.opacity(0.85))
                .padding(4)
                .minimumScaleFactor(0.5)
        }
    }

    private var initials: String {
        title.split(separator: " ").prefix(2)
            .compactMap { $0.first.map(String.init) }
            .joined()
            .uppercased()
    }

    private var colors: [Color] {
        let hue = Double(abs(title.hashValue) % 360) / 360.0
        return [
            Color(hue: hue, saturation: 0.5, brightness: 0.62),
            Color(hue: hue, saturation: 0.6, brightness: 0.40)
        ]
    }
}
