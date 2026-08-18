//
//  AddSourceFilterMenu.swift
//  Aidoku
//
//  Created by Skitty on 12/15/25.
//

import ExtensionRunner
import SwiftUI

struct AddSourceFilterMenu: View {
    private struct LanguageItem: Hashable, Identifiable {
        let id: String
        let title: String
    }

    private let languages: [LanguageItem]

    @State private var contentRatings: [String]
    @State private var selectedLanguages: [String]

    @Environment(\.dismiss) private var dismiss

    init() {
        let languageCodes = SourceLanguageFilter.availableLanguages(
            from: SourceManager.shared.sourceListLanguages
        )
        self.languages = languageCodes.map { code in
            .init(
                id: code,
                title: SourceLanguageFilter.displayName(for: code)
            )
        }
        self._contentRatings = State(initialValue: SettingsStore.shared.get(key: "Browse.contentRatings"))
        self._selectedLanguages = State(
            initialValue: Array(SourceLanguageFilter.selectedLanguages)
        )
    }

    var body: some View {
        Menu {
            Menu {
                ForEach(SourceContentRating.allCases, id: \.rawValue) { rating in
                    let index = contentRatings.firstIndex(where: { $0 == rating.stringValue })
                    Button {
                        if let index {
                            contentRatings.remove(at: index)
                        } else {
                            contentRatings.append(rating.stringValue)
                        }
                    } label: {
                        HStack {
                            Text(rating.title)
                            Spacer()
                            if index != nil {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                }
                .menuActionDismissDisabled()
            } label: {
                Label(NSLocalizedString("CONTENT_RATING"), systemImage: "exclamationmark.triangle.fill")
            }
            Menu {
                ForEach(languages) { language in
                    let index = selectedLanguages.firstIndex(where: { $0 == language.id })
                    Button {
                        if let index {
                            selectedLanguages.remove(at: index)
                        } else {
                            selectedLanguages.append(language.id)
                        }
                    } label: {
                        HStack {
                            Text(language.title)
                            Spacer()
                            if index != nil {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                }
                .menuActionDismissDisabled()
            } label: {
                Label(NSLocalizedString("LANGUAGES"), systemImage: "globe")
            }
        } label: {
            if #available(iOS 26.0, *) {
                Image(systemName: "line.3.horizontal.decrease")
            } else {
                Image(systemName: "line.3.horizontal.decrease.circle")
            }
        }
        .onChange(of: contentRatings) { _ in
            SettingsStore.shared.set(key: "Browse.contentRatings", value: contentRatings)
            NotificationCenter.default.post(name: .filterExternalSources, object: nil)
        }
        .onChange(of: selectedLanguages) { _ in
            SourceLanguageFilter.save(Set(selectedLanguages))
        }
    }
}

struct SourceLanguageFilterMenu: View {
    let availableLanguages: [String]

    @Binding var selectedLanguages: Set<String>

    var body: some View {
        Menu {
            ForEach(availableLanguages, id: \.self) { language in
                Button {
                    if selectedLanguages.contains(language) {
                        selectedLanguages.remove(language)
                    } else {
                        selectedLanguages.insert(language)
                    }
                    SourceLanguageFilter.save(selectedLanguages)
                } label: {
                    HStack {
                        Text(SourceLanguageFilter.displayName(for: language))
                        Spacer()
                        if selectedLanguages.contains(language) {
                            Image(systemName: "checkmark")
                        }
                    }
                }
                .menuActionDismissDisabled()
            }
        } label: {
            Label(NSLocalizedString("LANGUAGES"), systemImage: "globe")
        }
    }
}
