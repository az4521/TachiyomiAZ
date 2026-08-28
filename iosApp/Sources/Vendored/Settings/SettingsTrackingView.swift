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
    @State private var credentialTrackerId: String?
    @State private var credentialUsername = ""
    @State private var credentialPassword = ""
    @State private var credentialError: String?

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

            if !komgaSources.isEmpty || !kavitaSources.isEmpty || !suwayomiSources.isEmpty {
                Section(NSLocalizedString("ENHANCED_TRACKERS")) {
                    if !komgaSources.isEmpty {
                        NavigationLink("Komga") {
                            enhancedTrackerPage(name: "Komga", sources: komgaSources)
                        }
                    }
                    if !kavitaSources.isEmpty {
                        NavigationLink("Kavita") {
                            enhancedTrackerPage(name: "Kavita", sources: kavitaSources)
                        }
                    }
                    if !suwayomiSources.isEmpty {
                        NavigationLink("Suwayomi") {
                            enhancedTrackerPage(name: "Suwayomi", sources: suwayomiSources)
                        }
                    }
                }
            }

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
        .sheet(
            isPresented: Binding(
                get: { credentialTrackerId != nil },
                set: { if !$0 { credentialTrackerId = nil } }
            )
        ) {
            NavigationView {
                Form {
                    TextField(NSLocalizedString("USERNAME"), text: $credentialUsername)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField(NSLocalizedString("PASSWORD"), text: $credentialPassword)
                    if let credentialError {
                        Text(credentialError).foregroundStyle(.red)
                    }
                }
                .navigationTitle(
                    trackers.first(where: { $0.id == credentialTrackerId })?.name
                        ?? NSLocalizedString("TRACKERS")
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(NSLocalizedString("CANCEL")) { credentialTrackerId = nil }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(NSLocalizedString("LOGIN")) {
                            Task { await loginWithCredentials() }
                        }
                        .disabled(
                            credentialUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                || credentialPassword.isEmpty
                                || loadingTrackerId != nil
                        )
                    }
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .updateSourceList)) { _ in
            loadEnhancedTrackerSources()
        }
        .task {
            guard !loadedData else { return }

            for tracker in trackers {
                guard let oauthTracker = tracker as? OAuthTracker else { continue }
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

                        Toggle(isOn: stateBinding(sourceKey: source.key)) {
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
    func loadEnhancedTrackerSources() {
        let sources = SourceManager.shared.sources
        komgaSources = EnhancedSourceBridge.sources(for: "komga", in: sources)
        kavitaSources = EnhancedSourceBridge.sources(for: "kavita", in: sources)
        suwayomiSources = EnhancedSourceBridge.sources(for: "suwayomi", in: sources)

        for source in komgaSources + kavitaSources + suwayomiSources {
            enhancedTrackingStates[source.key] = !UserDefaults.standard.bool(
                forKey: "\(source.key).disableTracking"
            )
        }
    }


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
        } else if tracker is KitsuTracker || tracker is MangaUpdatesTracker {
            credentialUsername = ""
            credentialPassword = ""
            credentialError = nil
            credentialTrackerId = tracker.id
        } else if let tracker = tracker as? HikkaTracker,
                  let url = tracker.authenticationUrl {
            loadingTrackerId = tracker.id
            await UIApplication.shared.open(url)
            Task {
                // Approval happens in Safari. The app is normally suspended until the user comes
                // back; retry briefly on resume because the server may take a moment to publish
                // the granted token.
                defer { loadingTrackerId = nil }
                for _ in 0..<30 {
                    if (try? await tracker.claimToken()) != nil || tracker.isLoggedIn {
                        NotificationCenter.default.post(name: .updateTrackers, object: nil)
                        return
                    }
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                }
            }
        }
    }

    private func loginWithCredentials() async {
        guard let id = credentialTrackerId,
              let tracker = trackers.first(where: { $0.id == id }) else { return }
        loadingTrackerId = id
        credentialError = nil
        do {
            if let tracker = tracker as? KitsuTracker {
                try await tracker.logIn(username: credentialUsername, password: credentialPassword)
            } else if let tracker = tracker as? MangaUpdatesTracker {
                try await tracker.logIn(username: credentialUsername, password: credentialPassword)
            } else {
                return
            }
            credentialTrackerId = nil
            if let index = trackers.firstIndex(where: { $0.id == id }) {
                trackers[index] = tracker
            }
            NotificationCenter.default.post(name: .updateTrackers, object: nil)
        } catch {
            credentialError = error.localizedDescription
        }
        loadingTrackerId = nil
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
