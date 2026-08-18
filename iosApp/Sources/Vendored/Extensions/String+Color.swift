//
//  String+Color.swift
//  Aidoku
//
//  Created by Skitty on 9/18/25.
//

import SwiftUI
import UIKit

enum AppAccentColor {
    static let defaultHex = "#54759E"
    private static let defaultsKey = "Appearance.accentColor"
    private static let fileName = "accent-color.txt"

    static var storedHex: String {
        let explicitValue = Bundle.main.bundleIdentifier.flatMap {
            UserDefaults.standard.persistentDomain(forName: $0)?[defaultsKey]
                as? String
        }
        if let explicitValue, uiColor(from: explicitValue) != nil {
            return explicitValue.uppercased()
        }
        if
            let data = try? Data(contentsOf: persistenceURL),
            let value = String(data: data, encoding: .utf8),
            uiColor(from: value) != nil
        {
            UserDefaults.standard.set(value, forKey: defaultsKey)
            return value.uppercased()
        }
        return defaultHex
    }

    static var uiColor: UIColor {
        uiColor(from: storedHex) ?? UIColor(
            red: 84 / 255,
            green: 117 / 255,
            blue: 158 / 255,
            alpha: 1
        )
    }

    @MainActor
    static func set(_ color: UIColor) {
        let resolved = color.resolvedColor(
            with: UIScreen.main.traitCollection
        )
        let hex = hexString(from: resolved)
        persist(hex)
        UserDefaults.standard.set(hex, forKey: defaultsKey)
        UserDefaults.standard.synchronize()
        apply(resolved)
    }

    @MainActor
    static func applyStoredColor() {
        apply(uiColor)
    }

    @MainActor
    static func reset() {
        UserDefaults.standard.removeObject(forKey: defaultsKey)
        try? FileManager.default.removeItem(at: persistenceURL)
        apply(uiColor(from: defaultHex) ?? uiColor)
    }

    static func hexString(from color: UIColor) -> String {
        let resolved = color.resolvedColor(
            with: UIScreen.main.traitCollection
        )
        guard
            let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
            let converted = resolved.cgColor.converted(
                to: colorSpace,
                intent: .defaultIntent,
                options: nil
            ),
            let components = converted.components,
            components.count >= 3
        else {
            return defaultHex
        }
        let red = min(1, max(0, components[0]))
        let green = min(1, max(0, components[1]))
        let blue = min(1, max(0, components[2]))
        return String(
            format: "#%02X%02X%02X",
            Int(round(red * 255)),
            Int(round(green * 255)),
            Int(round(blue * 255))
        )
    }

    private static func uiColor(from value: String) -> UIColor? {
        var hex = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if hex.hasPrefix("#") {
            hex.removeFirst()
        }
        guard hex.count == 6, let rgb = Int(hex, radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }

    private static var persistenceURL: URL {
        FileManager.default.applicationSupportDirectory
            .appendingPathComponent(fileName, isDirectory: false)
    }

    private static func persist(_ value: String) {
        let directory = persistenceURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        try? Data(value.utf8).write(to: persistenceURL, options: .atomic)
    }

    @MainActor
    private static func apply(_ color: UIColor) {
        UIView.appearance().tintColor = color
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.tintColor = color
            }
        }
        NotificationCenter.default.post(name: .accentColorSetting, object: color)
        AppTheme.shared.update(color)
    }
}

@MainActor
final class AppTheme: ObservableObject {
    static let shared = AppTheme()

    @Published private(set) var accentColor: Color

    private init() {
        accentColor = Color(uiColor: AppAccentColor.uiColor)
    }

    fileprivate func update(_ color: UIColor) {
        accentColor = Color(uiColor: color)
    }
}

extension Color {
    /// The user-selected accent. `Color.accentColor` resolves the asset-catalog
    /// default in explicit drawing code and therefore ignores runtime changes.
    static var appAccent: Color {
        Color(uiColor: AppAccentColor.uiColor)
    }
}

private struct AppThemeModifier: ViewModifier {
    @ObservedObject private var theme = AppTheme.shared

    func body(content: Content) -> some View {
        content
            .tint(theme.accentColor)
            .accentColor(theme.accentColor)
    }
}

extension View {
    func appTheme() -> some View {
        modifier(AppThemeModifier())
    }
}

extension String {
    func toColor() -> Color {
        switch lowercased() {
            case "black": return Color.black
            case "blue": return Color.blue
            case "gray", "grey": return Color.gray
            case "green": return Color.green
            case "orange": return Color.orange
            case "pink": return Color.pink
            case "purple": return Color.purple
            case "indigo": return Color.indigo
            case "red": return Color.red
            case "white": return Color.white
            case "yellow": return Color.yellow
            case "primary": return Color.primary
            case "secondary": return Color.secondary
            default:
                // parse hex code
                var hex = if hasPrefix("#") {
                    String(dropFirst())
                } else {
                    self
                }
                if hex.count == 3 {
                    let r = hex[hex.startIndex]
                    let g = hex[hex.index(hex.startIndex, offsetBy: 1)]
                    let b = hex[hex.index(hex.startIndex, offsetBy: 2)]
                    hex = "\(r)\(r)\(g)\(g)\(b)\(b)"
                }
                if hex.count == 6, let intCode = Int(hex, radix: 16) {
                    let red = Double((intCode >> 16) & 0xFF) / 255
                    let green = Double((intCode >> 8) & 0xFF) / 255
                    let blue = Double(intCode & 0xFF) / 255
                    return Color(red: red, green: green, blue: blue)
                } else if hex.count == 8, let intCode = Int(hex, radix: 16) {
                    let red = Double((intCode >> 24) & 0xFF) / 255
                    let green = Double((intCode >> 16) & 0xFF) / 255
                    let blue = Double((intCode >> 8) & 0xFF) / 255
                    let alpha = Double(intCode & 0xFF) / 255
                    return Color(red: red, green: green, blue: blue, opacity: alpha)
                }
                return Color.black
        }
    }
}
