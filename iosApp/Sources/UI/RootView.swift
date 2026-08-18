import SwiftUI

/// Top-level navigation.
///
/// The drawer is the primary navigation, as on Android: no bottom bar unless the user asks for
/// one. iOS convention would be a tab bar, so it is offered in Settings, but the default is the
/// shape someone coming from the Android app already knows.
struct RootView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var destination: Destination = .library
    @State private var drawerOpen = false

    var body: some View {
        ZStack(alignment: .leading) {
            if settings.usesBottomTabs {
                tabbedContent
            } else {
                drawerOnlyContent
            }

            if drawerOpen {
                Color.black.opacity(0.35)
                    .ignoresSafeArea()
                    .transition(.opacity)
                    .onTapGesture { drawerOpen = false }
                    .accessibilityLabel("Close menu")
                    .accessibilityAddTraits(.isButton)
            }

            DrawerView(selection: $destination, isOpen: $drawerOpen)
                .frame(width: 300)
                .offset(x: drawerOpen ? 0 : -320)
                .ignoresSafeArea(edges: .vertical)
                .accessibilityHidden(!drawerOpen)
        }
        .animation(.easeOut(duration: 0.22), value: drawerOpen)
        .gesture(edgeDragGesture)
    }

    /// Drawer-only: one stack, content swapped by the drawer selection.
    private var drawerOnlyContent: some View {
        NavigationStack {
            destination.screen
                .navigationTitle(destination.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { menuButton }
        }
        .disabled(drawerOpen)
    }

    private var tabbedContent: some View {
        TabView(selection: $destination) {
            ForEach(Destination.allCases) { item in
                NavigationStack {
                    item.screen
                        .navigationTitle(item.title)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { menuButton }
                }
                .tabItem { Label(item.title, systemImage: item.icon) }
                .tag(item)
            }
        }
        .disabled(drawerOpen)
    }

    private var menuButton: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button {
                drawerOpen = true
            } label: {
                Image(systemName: "line.3.horizontal")
            }
            .accessibilityLabel("Menu")
        }
    }

    /// Edge swipe to open, swipe back to close -- the drawer is the only way to change screens in
    /// the default layout, so it needs to be reachable without aiming at the toolbar button.
    private var edgeDragGesture: some Gesture {
        DragGesture(minimumDistance: 20)
            .onEnded { value in
                if value.translation.width > 60, value.startLocation.x < 40 {
                    drawerOpen = true
                } else if value.translation.width < -60, drawerOpen {
                    drawerOpen = false
                }
            }
    }
}

enum Destination: String, Identifiable, CaseIterable {
    case library, updates, history, browse, extensions, more

    var id: String { rawValue }

    var title: String {
        switch self {
        case .library: return "Library"
        case .updates: return "Updates"
        case .history: return "History"
        case .browse: return "Browse"
        case .extensions: return "Extensions"
        case .more: return "More"
        }
    }

    var icon: String {
        switch self {
        case .library: return "books.vertical"
        case .updates: return "arrow.clockwise"
        case .history: return "clock"
        case .browse: return "safari"
        case .extensions: return "puzzlepiece.extension"
        case .more: return "ellipsis"
        }
    }

    @ViewBuilder var screen: some View {
        switch self {
        case .library:
            LibraryView()
        case .updates:
            NotYetPortedView(
                area: "Updates",
                detail: "The library update rules are already shared in :core-domain; this screen has not been built yet."
            )
        case .history:
            NotYetPortedView(
                area: "History",
                detail: "HistoryQueries is already shared; this screen has not been built yet."
            )
        case .browse:
            BrowseView()
        case .extensions:
            ExtensionsView()
        case .more:
            MoreView()
        }
    }
}
