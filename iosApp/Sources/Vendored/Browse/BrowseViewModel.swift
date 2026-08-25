//
//  BrowseViewModel.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 12/30/22.
//

import Foundation
import ExtensionRunner

@MainActor
class BrowseViewModel {
    var updatesSources: [SourceInfo2] = []
    var pinnedSources: [SourceInfo2] = []
    var installedSources: [SourceInfo2] = []

    var unfilteredExternalSources: [ExternalSourceInfo] = []

    var hasLegacySourceList = false

    // stored sources when searching
    private var query: String?
    private var storedUpdatesSources: [SourceInfo2]?
    private var storedPinnedSources: [SourceInfo2]?
    private var storedInstalledSources: [SourceInfo2]?

    private func getInstalledSources() async -> [SourceInfo2] {
        await SourceManager.shared.waitForSourcesLoad()
        return SourceManager.shared.sources
            .map { source in
                var info = source.toInfo()
                let externalInfo = unfilteredExternalSources.first { $0.id == info.sourceId }
                info.externalInfo = externalInfo
                return info
            }
    }

    /// Rebuilds both source sections from the same loaded snapshot and pin order.
    ///
    /// Previously `loadPinnedSources()` removed current pins from the installed array in place.
    /// After an unpin it had nothing that put that source back, so it vanished until a broader
    /// reload. Keeping both arrays derived from one snapshot makes pin, unpin, reload and search
    /// deterministic.
    func loadSourceSections() async {
        let allSources = await getInstalledSources()
        let sourcesById = Dictionary(uniqueKeysWithValues: allSources.map { ($0.sourceId, $0) })
        let pinIds = SourceManager.shared.pinnedSourceKeys()
        let pinned = pinIds.compactMap { sourcesById[$0] }
        let pinnedIds = Set(pinned.map(\.sourceId))
        let installed = allSources.filter { !pinnedIds.contains($0.sourceId) }

        // Drop only pins whose extension is no longer installed. SourceManager also performs the
        // legacy-key migration before this list is read.
        if pinned.count != pinIds.count {
            UserDefaults.standard.set(pinned.map(\.sourceId), forKey: "Browse.pinnedList")
        }

        if let query, !query.isEmpty {
            storedPinnedSources = pinned
            storedInstalledSources = installed
            let normalizedQuery = query.lowercased()
            pinnedSources = pinned.filter { $0.name.lowercased().contains(normalizedQuery) }
            installedSources = installed.filter { $0.name.lowercased().contains(normalizedQuery) }
        } else {
            pinnedSources = pinned
            installedSources = installed
        }
    }

    // load external source lists
    func loadExternalSources(reload: Bool = false) async {
        await SourceManager.shared.loadSourceLists(reload: reload)

        // ensure external sources have unique ids
        var sourceById: [String: ExternalSourceInfo] = [:]

        for sourceList in SourceManager.shared.sourceLists {
            if sourceList.legacy {
                hasLegacySourceList = true
            }
            for source in sourceList.sources {
                if let existing = sourceById[source.id] {
                    // if a newer version exists, replace it
                    if source.version > existing.version {
                        sourceById[source.id] = source
                    }
                } else {
                    sourceById[source.id] = source
                }
            }
        }

        unfilteredExternalSources = Array(sourceById.values)

        func updateExternalInfo(for property: inout [SourceInfo2]) {
            property = property.map { info in
                if let externalInfo = unfilteredExternalSources.first(where: { $0.id == info.sourceId }) {
                    var updatedInfo = info
                    updatedInfo.externalInfo = externalInfo
                    return updatedInfo
                }
                return info
            }
        }

        if query?.isEmpty ?? true {
            updateExternalInfo(for: &pinnedSources)
            updateExternalInfo(for: &installedSources)
        }
    }

    func loadUpdates() {
        guard let appVersionString = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String else { return }
        let appVersion = SemanticVersion(appVersionString)

        updatesSources = unfilteredExternalSources.compactMap { info -> SourceInfo2? in
            // check version availability
            if let minAppVersion = info.minAppVersion {
                let minAppVersion = SemanticVersion(minAppVersion)
                if minAppVersion > appVersion {
                    return nil
                }
            }
            if let maxAppVersion = info.maxAppVersion {
                let maxAppVersion = SemanticVersion(maxAppVersion)
                if maxAppVersion < appVersion {
                    return nil
                }
            }

            if let installedSource = installedSources.first(where: { $0.sourceId == info.id }) {
                if info.version > installedSource.version {
                    return info.toInfo()
                }
                return nil
            }
            if let pinnedSource = pinnedSources.first(where: { $0.sourceId == info.id }) {
                if info.version > pinnedSource.version {
                    return info.toInfo()
                }
                return nil
            }
            return nil
        }

    }

    // filter sources by search query
    func search(query: String?) {
        self.query = query
        if let query = query?.lowercased(), !query.isEmpty {
            // store full source arrays
            if storedUpdatesSources == nil {
                storedUpdatesSources = updatesSources
            }
            if storedPinnedSources == nil {
                storedPinnedSources = pinnedSources
            }
            if storedInstalledSources == nil {
                storedInstalledSources = installedSources
            }
            guard
                let storedUpdatesSources = storedUpdatesSources,
                let storedPinnedSources = storedPinnedSources,
                let storedInstalledSources = storedInstalledSources
            else { return }
            updatesSources = storedUpdatesSources.filter { $0.name.lowercased().contains(query) }
            pinnedSources = storedPinnedSources.filter { $0.name.lowercased().contains(query) }
            installedSources = storedInstalledSources.filter { $0.name.lowercased().contains(query) }
        } else {
            // reset search, restore source arrays
            if let storedUpdatesSources = storedUpdatesSources {
                updatesSources = storedUpdatesSources
                self.storedUpdatesSources = nil
            }
            if let storedPinnedSources = storedPinnedSources {
                pinnedSources = storedPinnedSources
                self.storedPinnedSources = nil
            }
            if let storedInstalledSources = storedInstalledSources {
                installedSources = storedInstalledSources
                self.storedInstalledSources = nil
            }
        }
    }
}
