import SwiftUI

/// Appearance settings: colour scheme and accent.
///
/// Purely presentational and iOS-only -- nothing about theming belongs in the shared modules,
/// which hold rules both apps must agree on rather than how either one looks.
enum AppColorScheme: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: return "System"
        case .light: return "Light"
        case .dark: return "Dark"
        }
    }

    var resolved: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

enum AppAccent: String, CaseIterable, Identifiable {
    case blue, indigo, purple, pink, red, orange, green, teal

    var id: String { rawValue }

    var title: String { rawValue.capitalized }

    var color: Color {
        switch self {
        case .blue: return .blue
        case .indigo: return .indigo
        case .purple: return .purple
        case .pink: return .pink
        case .red: return .red
        case .orange: return .orange
        case .green: return .green
        case .teal: return .teal
        }
    }
}
