//
//  SettingsAboutView.swift
//  Aidoku
//
//  Created by Skitty on 9/19/25.
//

import SwiftUI

struct SettingsAboutView: View {
    var body: some View {
        List {
            Section {
                HStack {
                    Text(NSLocalizedString("VERSION"))
                    Spacer()
                    let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
                    ?? NSLocalizedString("UNKNOWN")
                    Text(version)
                        .foregroundStyle(.secondary)
                }

                HStack {
                    Text(NSLocalizedString("BUILD"))
                    Spacer()
                    let version = Bundle.main.infoDictionary?["CFBundleVersion"] as? String
                    ?? NSLocalizedString("UNKNOWN")
                    Text(version)
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                SettingView(setting: .init(
                    title: NSLocalizedString("GITHUB_REPO"),
                    value: .link(.init(url: "https://github.com/az4521/TachiyomiAZiOS"))
                ))
                SettingView(setting: .init(
                    title: NSLocalizedString("DISCORD_SERVER"),
                    value: .link(.init(url: "https://discord.gg/mihon", external: true))
                ))
                SettingView(setting: .init(
                    title: NSLocalizedString("SUPPORT_VIA_KOFI"),
                    value: .link(.init(url: "https://ko-fi.com/az4521", external: true))
                ))
            }

            Section {
                NavigationLink {
                    SettingsCreditsView()
                } label: {
                    Text(NSLocalizedString(
                        "CREDITS",
                        value: "Credits",
                        comment: "About page credits link"
                    ))
                }
            }
        }
        .navigationTitle(NSLocalizedString("ABOUT"))
    }
}

private struct SettingsCreditsView: View {
    private struct Credit: Identifiable {
        let name: String
        let repository: String

        var id: String { repository }
        var url: URL? { URL(string: "https://github.com/\(repository)") }
    }

    private let credits = [
        Credit(name: "Aidoku", repository: "Aidoku/Aidoku"),
        Credit(name: "Suwayomi", repository: "Suwayomi/Suwayomi-Server"),
        Credit(name: "TachiyomiX", repository: "mihonapp/tachiyomix")
    ]

    var body: some View {
        List(credits) { credit in
            if let url = credit.url {
                Link(destination: url) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(credit.name)
                                .foregroundStyle(.primary)
                            Text("github.com/\(credit.repository)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "arrow.up.right.square")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle(NSLocalizedString(
            "CREDITS",
            value: "Credits",
            comment: "Credits page title"
        ))
    }
}
