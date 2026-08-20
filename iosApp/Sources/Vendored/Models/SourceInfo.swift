//
//  SourceInfo.swift
//  Aidoku
//
//  Created by Skitty on 12/30/22.
//

import Foundation
import ExtensionRunner

struct SourceInfo2: Hashable {
    let sourceId: String

    var iconUrl: URL?
    var name: String
    var altNames: [String] = []
    var languages: [String]
    var version: Int
    var contentRating: SourceContentRating
    var external: Bool = true

    var externalInfo: ExternalSourceInfo?

    var isMultiLanguage: Bool {
        languages.isEmpty || languages.count > 1 || languages.first == "multi"
    }
}

enum SourceLanguageFilter {
    static let settingsKey = "Browse.languages"

    static var currentLanguageCode: String {
        Locale.current.languageCode?.lowercased() ?? "en"
    }

    static var selectedLanguages: Set<String> {
        let stored = UserDefaults.standard.stringArray(forKey: settingsKey)
            ?? ["multi", currentLanguageCode]
        return Set(stored.map(normalize))
    }

    static func save(_ languages: Set<String>) {
        UserDefaults.standard.set(
            languages.sorted(),
            forKey: settingsKey
        )
        NotificationCenter.default.post(
            name: .filterExternalSources,
            object: nil
        )
    }

    static func matches(
        _ languages: [String],
        selected: Set<String> = selectedLanguages
    ) -> Bool {
        let sourceLanguages = languages.isEmpty
            ? ["multi"]
            : languages.map(normalize)
        return sourceLanguages.contains { language in
            selected.contains(language) || selected.contains(
                language.split(separator: "-", maxSplits: 1)
                    .first.map(String.init) ?? language
            )
        }
    }

    static func availableLanguages<S: Sequence>(
        from languages: S
    ) -> [String] where S.Element == String {
        var codes = Set(languages.map(normalize))
        codes.insert("multi")
        codes.insert(currentLanguageCode)
        return codes.sorted { lhs, rhs in
            if lhs == "multi" { return true }
            if rhs == "multi" { return false }
            return displayName(for: lhs).localizedStandardCompare(
                displayName(for: rhs)
            ) == .orderedAscending
        }
    }

    static func displayName(for language: String) -> String {
        let language = normalize(language)
        if language == "multi" {
            return NSLocalizedString("ALL")
        }
        return Locale.current.localizedString(forIdentifier: language)
            ?? language.uppercased()
    }

    static func displayName(for languages: [String]) -> String {
        let languages = languages.isEmpty ? ["multi"] : languages
        return languages
            .map(displayName)
            .joined(separator: ", ")
    }

    private static func normalize(_ language: String) -> String {
        let normalized = language
            .replacingOccurrences(of: "_", with: "-")
            .lowercased()
        return normalized == "all" ? "multi" : normalized
    }
}
