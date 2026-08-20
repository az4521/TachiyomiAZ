//
//  SettingsTrackingView.swift
//  Aidoku
//
//  Created by Skitty on 9/19/25.
//

import ExtensionRunner
import AuthenticationServices
import SwiftUI

struct SettingsTrackingView: View {
    @State private var trackers: [Tracker]
    @State private var trackersNeedingRelogin: Set<String> = []

    @State private var komgaSources: [ExtensionRunner.Source] = []
    @State private var kavitaSources: [ExtensionRunner.Source] = []
    @State private var suwayomiSources: [ExtensionRunner.Source] = []
    @State private var enhancedTrackingStates: [String: Bool] = [:]

    @State private var loadedData = false
    @State private var loadingTrackerId: String?
    @State private var logoutTrackerName: String?
    @State private var showLogoutAlert = false

    private let iconSize: CGFloat = 42
    private let iconCornerRadius: CGFloat = 42 * 0.225

    // empty view controller to support login view presentation
    private static var loginShimController = LoginShimViewController()

    init() {
        self._trackers = State(initialValue: TrackerManager.trackers.filter { !($0 is EnhancedTracker) })
    }

    var body: some View {
        List {
            Section {
                SettingView(setting: .init(
                    key: "Tracking.updateAfterReading",
                    title: NSLocalizedString("UPDATE_AFTER_READING"),
                    value: .toggle(.init())
                ))
            } footer: {
                Text(NSLocalizedString("UPDATE_AFTER_READING_INFO"))
            }

            Section {
                SettingView(setting: .init(
                    key: "Tracking.autoSyncFromTracker",
                    title: NSLocalizedString("AUTO_SYNC_HISTORY"),
                    value: .toggle(.init())
                ))
            } footer: {
                Text(NSLocalizedString("AUTO_SYNC_HISTORY_INFO"))
            }

            Section(NSLocalizedString("TRACKERS")) {
                ForEach(trackers.indices, id: \.self) { index in
                    let tracker = trackers[index]
                    let needsRelogin = trackersNeedingRelogin.contains(tracker.id)
                    Button {
                        if tracker.isLoggedIn && !needsRelogin {
                            logoutTrackerName = tracker.name
                            showLogoutAlert = true
                        } else {
                            Task {
                                await login(to: tracker)
                            }
                        }
                    } label: {
                        HStack(spacing: 12) {
                            if let icon = tracker.icon {
                                Image(uiImage: icon)
                                    .resizable()
                                    .frame(width: iconSize, height: iconSize)
                                    .clipShape(RoundedRectangle(cornerRadius: iconCornerRadius))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: iconCornerRadius)
                                            .strokeBorder(Color(UIColor.quaternarySystemFill), lineWidth: 1)
                                    )
                            }
                            Text(tracker.name)
                                .foregroundStyle(.primary)
                                .tint(.primary)

                            Spacer()

                            if loadingTrackerId == tracker.id {
                                ProgressView()
                                    .progressViewStyle(.circular)
                            } else if needsRelogin {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundStyle(.yellow)
                            } else if tracker.isLoggedIn {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                    }
                    .contextMenu {
                        if tracker.isLoggedIn {
                            Button {
                                Task {
                                    await login(to: tracker)
                                }
                            } label: {
                                Label(NSLocalizedString("REFRESH_LOGIN"), systemImage: "arrow.clockwise")
                            }
                        }
                    }
                }
            }

            // Enhanced trackers (Komga, Kavita, Suwayomi) are not built -- see
            // Vendored/_excluded/Tracking -- so there is no section to show.

        }
        .navigationTitle(NSLocalizedString("TRACKING"))
        .alert(String(format: NSLocalizedString("LOGOUT_FROM_%@"), logoutTrackerName ?? ""), isPresented: $showLogoutAlert) {
            Button(NSLocalizedString("CANCEL"), role: .cancel) {}
            Button(NSLocalizedString("LOGOUT"), role: .destructive) {
                if let name = logoutTrackerName, let tracker = trackers.first(where: { $0.name == name }) {
                    Task {
                        // Call tracker logout to clear authentication data
                        do {
                            try await tracker.logout()

                            if let index = trackers.firstIndex(where: { $0.id == tracker.id }) {
                                trackers[index] = tracker
                            }
                        } catch {
                            LogManager.logger.error("Unable to log out from \(tracker.name) tracker: \(error)")
                        }
                        // Remove all tracked items for this tracker
                        await DatabaseContainer.shared.performBackgroundTask { @Sendable context in
                            SharedDataStore.shared.removeTracks(trackerId: tracker.id, context: context)
                        }
                        NotificationCenter.default.post(name: .updateTrackers, object: nil)
                    }
                }
            }
        } message: {
            Text(NSLocalizedString("TRACKER_LOGOUT_INFO"))
        }
        .onReceive(NotificationCenter.default.publisher(for: .updateSourceList)) { _ in
            loadEnhancedTrackerSources()
        }
        .task {
            guard !loadedData else { return }

            for tracker in trackers {
                guard let oauthTracker = tracker as? OAuthTracker else { return }
                let needsRelogin = await oauthTracker.oauthClient.tokens?.askedForRefresh == true
                if needsRelogin {
                    trackersNeedingRelogin.insert(tracker.id)
                }
            }

            loadEnhancedTrackerSources()

            loadedData = true
        }
    }

    func enhancedTrackerPage(name: String, sources: [ExtensionRunner.Source]) -> some View {
        List {
            Section {
                ForEach(sources) { source in
                    HStack(spacing: 12) {
                        SourceIconView(sourceId: source.key, imageUrl: source.imageUrl)

                        Text(source.name)
                            .foregroundStyle(.primary)
                            .tint(.primary)

                        Spacer()

                        Toggle(isOn: stateBinding(sourceKey: source.id)) {
                            EmptyView()
                        }
                    }
                }
            } footer: {
                Text(NSLocalizedString("ENHANCED_TRACKERS_TOGGLE_INFO"))
            }
        }
        .navigationTitle(name)
    }
}

extension SettingsTrackingView {
    func loadEnhancedTrackerSources() {}


    func handleEnhancedTrackingStateChange(sourceKey: String, enabled: Bool) {
        UserDefaults.standard.set(!enabled, forKey: "\(sourceKey).disableTracking")
    }


    func login(to tracker: Tracker) async {
        if let tracker = tracker as? OAuthTracker {
            guard let url = await tracker.getAuthenticationUrl() else { return }
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "aidoku") { callbackURL, error in
                if let error {
                    LogManager.logger.error("Tracker authentication error: \(error.localizedDescription)")
                }
                if let callbackURL {
                    Task { @MainActor in
                        let loadingIndicator = UIActivityIndicatorView(style: .medium)
                        loadingIndicator.startAnimating()

                        loadingTrackerId = tracker.id
                        await tracker.handleAuthenticationCallback(url: callbackURL)
                        loadingTrackerId = nil

                        if let index = trackers.firstIndex(where: { $0.id == tracker.id }) {
                            trackers[index] = tracker
                        }

                        if tracker.isLoggedIn {
                            await tracker.oauthClient.loadTokens()
                            trackersNeedingRelogin.remove(tracker.id)
                        }

                        NotificationCenter.default.post(name: .updateTrackers, object: nil)
                    }
                }
            }
            session.presentationContextProvider = Self.loginShimController
            session.start()
        }
    }
}

extension SettingsTrackingView {
    func stateBinding(sourceKey: String) -> Binding<Bool> {
        Binding {
            enhancedTrackingStates[sourceKey, default: true]
        } set: {
            enhancedTrackingStates[sourceKey] = $0
            handleEnhancedTrackingStateChange(sourceKey: sourceKey, enabled: $0)
        }
    }
}
