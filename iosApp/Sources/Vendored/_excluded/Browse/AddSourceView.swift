//
//  AddSourceView.swift
//  Aidoku
//
//  Created by Skitty on 5/23/25.
//

import ExtensionRunner
import SwiftUI

struct AddSourceView: View {
    @State private var showLocalSetup = false
    @State private var showKomgaSetup = false
    @State private var showKavitaSetup = false

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PlatformNavigationStack {
            List {
                builtInSources
            }
            .contentMarginsPlease(.top, 4)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    CloseButton {
                        dismiss()
                    }
                }
            }
            .navigationTitle(NSLocalizedString("ADD_SOURCE"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    var builtInSources: some View {
        Section(NSLocalizedString("BUILT_IN_SOURCES")) {
            if !SourceManager.shared.sources.contains(where: { $0.key == LocalSourceRunner.sourceKey }) {
                ExternalSourceTableCell(
                    source: .init(
                        sourceId: LocalSourceRunner.sourceKey,
                        name: NSLocalizedString("LOCAL_FILES"),
                        languages: ["multi"],
                        version: 1,
                        contentRating: .safe
                    ),
                    subtitle: NSLocalizedString("LOCAL_FILES_TAGLINE"),
                    onGet: {
                        showLocalSetup = true
                        return true
                    }
                )
                .background(NavigationLink("", destination: LocalSetupView(), isActive: $showLocalSetup).hidden())
            }

            ExternalSourceTableCell(
                source: .init(
                    sourceId: "komga",
                    name: NSLocalizedString("KOMGA"),
                    languages: ["multi"],
                    version: 1,
                    contentRating: .safe
                ),
                subtitle: NSLocalizedString("KOMGA_TAGLINE"),
                onGet: {
                    showKomgaSetup = true
                    return true
                }
            )
            .background(NavigationLink("", destination: KomgaSetupView(), isActive: $showKomgaSetup).hidden())

            ExternalSourceTableCell(
                source: .init(
                    sourceId: "kavita",
                    name: NSLocalizedString("KAVITA"),
                    languages: ["multi"],
                    version: 1,
                    contentRating: .safe
                ),
                subtitle: NSLocalizedString("KAVITA_TAGLINE"),
                onGet: {
                    showKavitaSetup = true
                    return true
                }
            )
            .background(NavigationLink("", destination: KavitaSetupView(), isActive: $showKavitaSetup).hidden())

        }
    }

}

struct ExtensionManagementView: View {
    @State private var manifests: [JVMExtensionManifest] = []
    @State private var repositories = TachiyomiXJarRepository.repositories()
    @State private var selectedLanguages = SourceLanguageFilter.selectedLanguages
    @State private var errorMessage: String?

    @EnvironmentObject private var path: NavigationCoordinator

    private var availableLanguages: [String] {
        SourceLanguageFilter.availableLanguages(
            from: SourceManager.shared.sources.flatMap(\.languages)
        )
    }

    private var filteredManifests: [JVMExtensionManifest] {
        manifests.filter { manifest in
            let sources = extensionSources(for: manifest.id)
            return sources.isEmpty || sources.contains {
                SourceLanguageFilter.matches(
                    $0.languages,
                    selected: selectedLanguages
                )
            }
        }
    }

    var body: some View {
        List {
            Section {
                if filteredManifests.isEmpty {
                    Text(
                        manifests.isEmpty
                            ? "No extensions are installed."
                            : "No extensions match the selected languages."
                    )
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(filteredManifests, id: \.id) { manifest in
                        Button {
                            path.push(ExtensionDetailView(manifest: manifest))
                        } label: {
                            extensionRow(manifest)
                        }
                        .buttonStyle(.plain)
                    }
                }
            } header: {
                Text("Installed")
            }

            Section {
                if repositories.isEmpty {
                    Text(
                        "No extension repositories are configured. " +
                            "Add one to browse available extensions."
                    )
                    .foregroundStyle(.secondary)
                } else {
                    ForEach(repositories) { repository in
                        Button {
                            path.push(ExtensionCatalogView(repository: repository))
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(repository.name)
                                    .foregroundStyle(.primary)
                                Text(repository.catalogURL.absoluteString)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Button {
                    path.push(ExtensionRepositoryListView())
                } label: {
                    Label("Manage Repositories", systemImage: "shippingbox")
                }
            } header: {
                Text("Repositories")
            } footer: {
                Text(
                    "TachiyomiAZ does not include or recommend any extension " +
                        "repository. Only add repositories you trust."
                )
            }
        }
        .navigationTitle(
            NSLocalizedString(
                "EXTENSIONS",
                value: "Extensions",
                comment: "Extensions drawer destination"
            )
        )
        .navigationBarTitleDisplayMode(.automatic)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                SourceLanguageFilterMenu(
                    availableLanguages: availableLanguages,
                    selectedLanguages: $selectedLanguages
                )
            }
        }
        .task {
            await reload()
        }
        .onAppear {
            repositories = TachiyomiXJarRepository.repositories()
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .updateSourceList)
        ) { _ in
            Task {
                await reload()
            }
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .filterExternalSources)
        ) { _ in
            selectedLanguages = SourceLanguageFilter.selectedLanguages
        }
        .refreshable {
            await reload()
        }
        .alert(
            "Extension Error",
            isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )
        ) {
            Button(NSLocalizedString("OK"), role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func extensionRow(_ manifest: JVMExtensionManifest) -> some View {
        let sources = extensionSources(for: manifest.id)
        return HStack(spacing: 12) {
            AsyncImage(url: manifest.iconURL) { image in
                image.resizable().scaledToFit()
            } placeholder: {
                Image(systemName: "shippingbox.fill")
                    .foregroundStyle(.secondary)
            }
            .frame(width: 40, height: 40)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 3) {
                Text(manifest.name)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(
                    "\(manifest.version) • " +
                        "\(sources.count) " +
                        (sources.count == 1 ? "source" : "sources")
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.bold())
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    private func reload() async {
        do {
            manifests = try await JVMSourceRuntime.shared.installedManifests()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct ExtensionDetailView: View {
    let manifest: JVMExtensionManifest

    @State private var showingUninstallConfirmation = false
    @State private var uninstalling = false
    @State private var errorMessage: String?

    @EnvironmentObject private var path: NavigationCoordinator

    private var sources: [ExtensionRunner.Source] {
        extensionSources(for: manifest.id)
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 16) {
                    AsyncImage(url: manifest.iconURL) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        Image(systemName: "shippingbox.fill")
                            .foregroundStyle(.secondary)
                    }
                    .frame(width: 56, height: 56)
                    .clipShape(RoundedRectangle(cornerRadius: 8))

                    VStack(alignment: .leading, spacing: 4) {
                        Text(manifest.name)
                            .font(.headline)
                        Text(manifest.version)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if let extensionLibrary = manifest.extensionLibrary {
                            Text(extensionLibrary)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 4)
            }

            Section("Source Settings") {
                if sources.isEmpty {
                    Text("This extension did not load any sources.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(sources, id: \.key) { source in
                        Button {
                            path.push(
                                SourceSettingsView(
                                    source: source,
                                    showsCloseButton: false
                                )
                            )
                        } label: {
                            HStack(spacing: 12) {
                                SourceIconView(
                                    sourceId: source.key,
                                    imageUrl: source.imageUrl,
                                    iconSize: 32
                                )
                                Text(source.name)
                                    .foregroundStyle(.primary)
                                Spacer()
                                Text(
                                    SourceLanguageFilter.displayName(
                                        for: source.languages
                                    )
                                )
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                Image(systemName: "chevron.right")
                                    .font(.caption.bold())
                                    .foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Section {
                Button(role: .destructive) {
                    showingUninstallConfirmation = true
                } label: {
                    HStack {
                        Spacer()
                        if uninstalling {
                            ProgressView()
                        } else {
                            Text("Uninstall Extension")
                        }
                        Spacer()
                    }
                }
                .disabled(uninstalling)
            }
        }
        .navigationTitle(manifest.name)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialogOrAlert(
            "Uninstall Extension",
            isPresented: $showingUninstallConfirmation,
            titleVisibility: .visible
        ) {
            Button("Uninstall", role: .destructive) {
                uninstall()
            }
        } message: {
            Text(
                "This removes \(manifest.name) and all sources it provides."
            )
        }
        .alert(
            "Extension Error",
            isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )
        ) {
            Button(NSLocalizedString("OK"), role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func uninstall() {
        uninstalling = true
        Task {
            defer {
                uninstalling = false
            }
            do {
                try await SourceManager.shared.uninstallTachiyomiXExtension(
                    id: manifest.id
                )
                path.pop()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

private func extensionSources(
    for extensionId: String
) -> [ExtensionRunner.Source] {
    SourceManager.shared.sources
        .filter {
            ($0.runner as? TachiyomiXSourceRunner)?.extensionId == extensionId
        }
        .sorted {
            let nameOrder = $0.name.localizedStandardCompare($1.name)
            if nameOrder == .orderedSame {
                return SourceLanguageFilter.displayName(for: $0.languages)
                    .localizedStandardCompare(
                        SourceLanguageFilter.displayName(for: $1.languages)
                    ) == .orderedAscending
            }
            return nameOrder == .orderedAscending
        }
}

private struct ExtensionRepositoryListView: View {
    @State private var repositories = TachiyomiXJarRepository.repositories()
    @State private var repositoryURL = ""
    @State private var validating = false
    @State private var errorMessage: String?

    @EnvironmentObject private var path: NavigationCoordinator

    var body: some View {
        List {
            Section {
                if repositories.isEmpty {
                    Text(
                        "No extension repositories are configured. " +
                            "Add a repository URL below to browse extensions."
                    )
                    .foregroundStyle(.secondary)
                } else {
                    ForEach(repositories) { repository in
                        Button {
                            path.push(ExtensionCatalogView(repository: repository))
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(repository.name)
                                    .foregroundStyle(.primary)
                                Text(repository.catalogURL.absoluteString)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                    .onDelete(perform: deleteRepositories)
                }
            } header: {
                Text("Repositories")
            } footer: {
                Text(
                    "TachiyomiAZ does not include or recommend any extension " +
                        "repository. Only add repositories you trust."
                )
            }

            Section {
                TextField("Repository URL", text: $repositoryURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                Button {
                    addRepository()
                } label: {
                    if validating {
                        ProgressView()
                    } else {
                        Label("Add Repository", systemImage: "plus")
                    }
                }
                .disabled(
                    validating ||
                        repositoryURL.trimmingCharacters(
                            in: .whitespacesAndNewlines
                        ).isEmpty
                )
            } header: {
                Text("Add Repository")
            } footer: {
                Text(
                    "Enter an index.pb or index.json URL, or its directory. " +
                        "The repository is validated before it is saved."
                )
            }
        }
        .navigationTitle("Extension Repositories")
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            "Repository Error",
            isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )
        ) {
            Button(NSLocalizedString("OK"), role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func addRepository() {
        validating = true
        Task {
            defer {
                validating = false
            }
            do {
                _ = try await TachiyomiXJarRepository.addRepository(
                    from: repositoryURL
                )
                repositories = TachiyomiXJarRepository.repositories()
                repositoryURL = ""
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func deleteRepositories(at offsets: IndexSet) {
        var updated = repositories
        updated.remove(atOffsets: offsets)
        do {
            try TachiyomiXJarRepository.save(repositories: updated)
            repositories = updated
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct ExtensionCatalogView: View {
    let repository: TachiyomiXJarRepository.Repository

    @State private var catalog: TachiyomiXJarRepository.Catalog?
    @State private var installedVersions: [String: String] = [:]
    @State private var unhealthyExtensions: Set<String> = []
    @State private var installing: Set<String> = []
    @State private var searchText = ""
    @State private var selectedLanguages = SourceLanguageFilter.selectedLanguages
    @State private var errorMessage: String?

    private var availableLanguages: [String] {
        SourceLanguageFilter.availableLanguages(
            from: catalog?.extensionList.extensions.flatMap {
                $0.sources.map(\.language)
            } ?? []
        )
    }

    private var extensions: [TachiyomiXJarRepository.Catalog.Extension] {
        guard let catalog else { return [] }
        let supported = catalog.extensionList.extensions.filter(
            \.usesSupportedExtensionLibrary
        )
        let languageFiltered = supported.filter { entry in
            SourceLanguageFilter.matches(
                entry.sources.map(\.language),
                selected: selectedLanguages
            )
        }
        guard !searchText.isEmpty else { return languageFiltered }
        let query = searchText.localizedLowercase
        return languageFiltered.filter {
            $0.name.localizedLowercase.contains(query) ||
                $0.sources.contains {
                    $0.name.localizedLowercase.contains(query)
                }
        }
    }

    var body: some View {
        List {
            if catalog == nil, errorMessage == nil {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            } else if extensions.isEmpty {
                Text(NSLocalizedString("NO_RESULTS"))
                    .frame(maxWidth: .infinity)
            } else {
                ForEach(extensions) { entry in
                    HStack(spacing: 12) {
                        AsyncImage(url: entry.resources.iconUrl) { image in
                            image.resizable().scaledToFit()
                        } placeholder: {
                            Image(systemName: "books.vertical.fill")
                                .foregroundStyle(.secondary)
                        }
                        .frame(width: 40, height: 40)
                        .clipShape(RoundedRectangle(cornerRadius: 6))

                        VStack(alignment: .leading, spacing: 3) {
                            Text(entry.name)
                                .lineLimit(1)
                            Text(
                                "\(entry.extensionLib) • " +
                                    Array(Set(entry.sources.map(\.language)))
                                    .sorted()
                                    .joined(separator: ", ")
                            )
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            if entry.isNsfw {
                                Text("NSFW")
                                    .font(.caption2.bold())
                                    .foregroundStyle(.red)
                            }
                        }

                        Spacer()

                        Button(buttonTitle(for: entry)) {
                            install(entry)
                        }
                        .buttonStyle(.bordered)
                        .disabled(
                            installing.contains(entry.packageName) ||
                                (
                                    installedVersions[entry.packageName] ==
                                        entry.versionName &&
                                    !unhealthyExtensions.contains(
                                        entry.packageName
                                    )
                                )
                        )
                        .overlay {
                            if installing.contains(entry.packageName) {
                                ProgressView()
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(repository.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                SourceLanguageFilterMenu(
                    availableLanguages: availableLanguages,
                    selectedLanguages: $selectedLanguages
                )
            }
        }
        .searchable(
            text: $searchText,
            prompt: NSLocalizedString("SEARCH")
        )
        .task {
            await reload()
        }
        .refreshable {
            await reload()
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .filterExternalSources)
        ) { _ in
            selectedLanguages = SourceLanguageFilter.selectedLanguages
        }
        .alert(
            "Extension Error",
            isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )
        ) {
            Button(NSLocalizedString("OK"), role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func buttonTitle(
        for entry: TachiyomiXJarRepository.Catalog.Extension
    ) -> String {
        guard let installed = installedVersions[entry.packageName] else {
            return "Get"
        }
        if unhealthyExtensions.contains(entry.packageName) {
            return "Repair"
        }
        return installed == entry.versionName
            ? NSLocalizedString("INSTALLED")
            : "Update"
    }

    private func reload() async {
        do {
            async let catalog = TachiyomiXJarRepository.fetchCatalog(
                from: repository.catalogURL
            )
            let manifests = try await JVMSourceRuntime.shared
                .installedManifests()
            let verifiedManifests = try await JVMSourceRuntime.shared
                .verifiedInstalledManifests()
            installedVersions = Dictionary(
                uniqueKeysWithValues: manifests.map { ($0.id, $0.version) }
            )
            unhealthyExtensions = Set(manifests.map(\.id)).subtracting(
                verifiedManifests.map(\.id)
            )
            self.catalog = try await catalog
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func install(
        _ entry: TachiyomiXJarRepository.Catalog.Extension
    ) {
        installing.insert(entry.packageName)
        Task {
            defer {
                installing.remove(entry.packageName)
            }
            do {
                let manifest = try await SourceManager.shared
                    .installTachiyomiXExtension(entry)
                installedVersions[entry.packageName] = manifest.version
                unhealthyExtensions.remove(entry.packageName)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
