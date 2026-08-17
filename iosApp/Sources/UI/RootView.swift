import SwiftUI

/// Top-level navigation, shaped after the Android app: a bottom tab bar for the main destinations
/// and a slide-over drawer for everything else.
///
/// iOS convention would be a tab bar alone, but the point of this port is that someone who uses the
/// Android app knows where things are, so the drawer stays.
struct RootView: View {
    @State private var tab: Destination = .library
    @State private var drawerOpen = false

    var body: some View {
        ZStack(alignment: .leading) {
            TabView(selection: $tab) {
                ForEach(Destination.tabs) { destination in
                    NavigationStack {
                        destination.screen
                            .navigationTitle(destination.title)
                            .navigationBarTitleDisplayMode(.inline)
                            .toolbar {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button {
                                        openDrawer()
                                    } label: {
                                        Image(systemName: "line.3.horizontal")
                                    }
                                    .accessibilityLabel("Menu")
                                }
                            }
                    }
                    .tabItem { Label(destination.title, systemImage: destination.icon) }
                    .tag(destination)
                }
            }
            .disabled(drawerOpen)

            if drawerOpen {
                Color.black.opacity(0.35)
                    .ignoresSafeArea()
                    .transition(.opacity)
                    .onTapGesture { closeDrawer() }
                    .accessibilityLabel("Close menu")
            }

            DrawerView(selection: $tab, isOpen: $drawerOpen)
                .frame(width: 300)
                .offset(x: drawerOpen ? 0 : -320)
                .ignoresSafeArea(edges: .vertical)
        }
        .animation(.easeOut(duration: 0.22), value: drawerOpen)
        .gesture(
            DragGesture(minimumDistance: 20)
                .onEnded { value in
                    if value.translation.width > 60, value.startLocation.x < 40 {
                        openDrawer()
                    } else if value.translation.width < -60, drawerOpen {
                        closeDrawer()
                    }
                }
        )
    }

    private func openDrawer() {
        drawerOpen = true
    }

    private func closeDrawer() {
        drawerOpen = false
    }
}

enum Destination: String, Identifiable, CaseIterable {
    case library, updates, history, browse, more

    var id: String { rawValue }

    /// The bottom bar carries the same five destinations the Android app puts there.
    static var tabs: [Destination] { allCases }

    var title: String {
        switch self {
        case .library: return "Library"
        case .updates: return "Updates"
        case .history: return "History"
        case .browse: return "Browse"
        case .more: return "More"
        }
    }

    var icon: String {
        switch self {
        case .library: return "books.vertical"
        case .updates: return "arrow.clockwise"
        case .history: return "clock"
        case .browse: return "safari"
        case .more: return "ellipsis"
        }
    }

    @ViewBuilder var screen: some View {
        switch self {
        case .library: LibraryView()
        case .updates: NotYetPortedView(area: "Updates", detail: "Needs the library update rules in :core-domain wired to a background refresh.")
        case .history: NotYetPortedView(area: "History", detail: "HistoryQueries is already shared; this screen just has not been built yet.")
        case .browse: NotYetPortedView(area: "Browse", detail: "Blocked on source loading -- see the notes in IOS_PORT.md.")
        case .more: MoreView()
        }
    }
}
