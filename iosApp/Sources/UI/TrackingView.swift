import SwiftUI
import TachiyomiKit

/// Tracker accounts, from Settings.
struct TrackingSettingsView: View {
    @EnvironmentObject private var tracking: TrackingStore

    @State private var busy: TrackerService?
    @State private var error: String?

    var body: some View {
        List {
            Section {
                ForEach(TrackerService.allCases) { service in
                    row(service)
                }
            } header: {
                Text("Accounts")
            } footer: {
                Text("Tracks are stored in the shared manga_sync table, so they travel with a backup and match what the Android app writes.")
            }

            if let error {
                Section { Text(error).font(.footnote).foregroundStyle(.red) }
            }
        }
    }

    private func row(_ service: TrackerService) -> some View {
        HStack {
            Text(service.title)
            Spacer()
            if busy == service {
                ProgressView().controlSize(.small)
            } else if tracking.isLoggedIn(service) {
                Button("Log out", role: .destructive) {
                    tracking.tracker(service)?.logOut()
                }
                .font(.caption)
            } else {
                Button("Log in") {
                    Task { await logIn(service) }
                }
                .font(.caption)
            }
        }
    }

    private func logIn(_ service: TrackerService) async {
        busy = service
        defer { busy = nil }
        do {
            switch service {
            case .myAnimeList:
                try await (tracking.tracker(service) as? MyAnimeListTracker)?.logIn()
            case .aniList:
                try await (tracking.tracker(service) as? AniListTracker)?.logIn()
            }
            error = nil
        } catch {
            self.error = "\(service.title): \(error.localizedDescription)"
        }
    }
}

/// Binding a library entry to a tracker, and editing an existing track.
struct MangaTrackingView: View {
    let manga: Manga

    @EnvironmentObject private var tracking: TrackingStore

    @State private var tracks: [Track] = []
    @State private var searching: TrackerService?
    @State private var query = ""
    @State private var results: [TrackSearchResult] = []
    @State private var isSearching = false

    var body: some View {
        List {
            Section("Tracking") {
                if tracks.isEmpty {
                    Text("Not tracked.").foregroundStyle(.secondary)
                } else {
                    ForEach(tracks, id: \.media_id) { track in
                        trackRow(track)
                    }
                }
            }

            Section("Add") {
                ForEach(TrackerService.allCases) { service in
                    Button {
                        query = manga.title
                        searching = service
                        Task { await search(service) }
                    } label: {
                        HStack {
                            Text(service.title)
                            Spacer()
                            if !tracking.isLoggedIn(service) {
                                Text("Not logged in").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(!tracking.isLoggedIn(service))
                }
            }

            if searching != nil {
                Section(isSearching ? "Searching\u{2026}" : "Results") {
                    ForEach(results) { result in
                        Button {
                            Task { await bind(result) }
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(result.title)
                                if result.totalChapters > 0 {
                                    Text("\(result.totalChapters) chapters")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Tracking")
        .navigationBarTitleDisplayMode(.inline)
        .task { tracks = tracking.tracks(for: manga) }
    }

    private func trackRow(_ track: Track) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(track.title).font(.subheadline)
            Text("\(track.last_chapter_read)/\(track.total_chapters == 0 ? "?" : String(track.total_chapters))")
                .font(.caption)
                .foregroundStyle(.secondary)
            Picker("Status", selection: Binding(
                get: { TrackStatus(rawValue: track.status) ?? .reading },
                set: { newValue in Task { await tracking.setStatus(track, status: newValue) } }
            )) {
                ForEach(TrackStatus.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.menu)
        }
        .swipeActions {
            Button("Remove", role: .destructive) {
                Task {
                    await tracking.unbind(track, from: manga)
                    tracks = tracking.tracks(for: manga)
                }
            }
        }
    }

    private func search(_ service: TrackerService) async {
        isSearching = true
        defer { isSearching = false }
        results = (try? await tracking.tracker(service)?.search(query)) ?? []
    }

    private func bind(_ result: TrackSearchResult) async {
        guard let service = searching else { return }
        await tracking.bind(result, service: service, to: manga)
        tracks = tracking.tracks(for: manga)
        searching = nil
        results = []
    }
}
