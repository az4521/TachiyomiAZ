import SwiftUI
import TachiJVMRunner

/// Browsing one source: Popular, Latest (when the source has it), and Search with filters.
///
/// Paging, filters and what each mode returns are all the extension's business. This screen only
/// asks and renders -- if a source's "popular" is idiosyncratic, that is the source being itself.
struct SourceBrowseView: View {
    enum Mode: String, CaseIterable, Identifiable {
        case popular, latest, search
        var id: String { rawValue }
        var title: String {
            switch self {
            case .popular: return "Popular"
            case .latest: return "Latest"
            case .search: return "Search"
            }
        }
    }

    let source: SourceDescriptor

    @EnvironmentObject private var runtime: SourceRuntime
    @EnvironmentObject private var library: LibraryStore

    @State private var mode: Mode = .popular
    @State private var manga: [TachiyomiXManga] = []
    @State private var page = 1
    @State private var hasNextPage = false
    @State private var isLoading = false
    @State private var error: String?
    @State private var query = ""
    @State private var filters: [SourceFilter] = []
    @State private var filterStates: [Int: SourceFilterState] = [:]
    @State private var showingFilters = false

    private var availableModes: [Mode] {
        source.supportsLatest ? Mode.allCases : [.popular, .search]
    }

    private let columns = [GridItem(.adaptive(minimum: 108, maximum: 160), spacing: 12)]

    var body: some View {
        VStack(spacing: 0) {
            Picker("Mode", selection: $mode) {
                ForEach(availableModes) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            content
        }
        .navigationTitle(source.name)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(
            text: $query,
            placement: .navigationBarDrawer(displayMode: .always),
            prompt: "Search \(source.name)"
        )
        .onSubmit(of: .search) {
            mode = .search
            Task { await load(reset: true) }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showingFilters = true } label: {
                    Image(systemName: filterStates.isEmpty
                          ? "line.3.horizontal.decrease.circle"
                          : "line.3.horizontal.decrease.circle.fill")
                }
                .disabled(filters.isEmpty)
                .accessibilityLabel("Filters")
            }
        }
        .sheet(isPresented: $showingFilters) {
            NavigationView {
                SourceFilterSheet(filters: filters, states: $filterStates) {
                    mode = .search
                    Task { await load(reset: true) }
                }
            }
        }
        .task {
            await loadFilters()
            await load(reset: true)
        }
        .onChange(of: mode) { _ in Task { await load(reset: true) } }
        .refreshable { await load(reset: true) }
    }

    @ViewBuilder
    private var content: some View {
        if let error {
            VStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 34))
                    .foregroundStyle(.orange)
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                Button("Retry") { Task { await load(reset: true) } }
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if manga.isEmpty && isLoading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if manga.isEmpty {
            Text(mode == .search ? "No results." : "Nothing here.")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(Array(manga.enumerated()), id: \.offset) { _, item in
                        NavigationLink(destination: MangaDetailView(source: source, manga: item)) {
                            SourceMangaCell(manga: item)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(12)

                if hasNextPage {
                    Button {
                        Task { await load(reset: false) }
                    } label: {
                        if isLoading {
                            ProgressView().padding()
                        } else {
                            Text("Load more").padding()
                        }
                    }
                    .disabled(isLoading)
                }
            }
        }
    }

    private func loadFilters() async {
        filters = (try? await runtime.filters(source)) ?? []
    }

    private func load(reset: Bool) async {
        if reset {
            page = 1
            manga = []
            error = nil
        }
        isLoading = true
        defer { isLoading = false }

        do {
            let result: TachiyomiXMangaPage
            switch mode {
            case .popular:
                result = try await runtime.popular(source, page: page)
            case .latest:
                result = try await runtime.latest(source, page: page)
            case .search:
                let encoded = SourceFilterEncoder.encode(states: filterStates, filters: filters)
                result = try await runtime.search(
                    source,
                    query: query,
                    page: page,
                    filterStates: encoded
                )
            }
            manga.append(contentsOf: result.mangas)
            hasNextPage = result.hasNextPage
            page += 1
            error = nil
        } catch {
            // Sources fail for ordinary reasons -- Cloudflare, a dead mirror, a changed layout.
            // Surfacing the message beats a blank grid, since it is usually actionable.
            self.error = error.localizedDescription
        }
    }
}

struct SourceMangaCell: View {
    let manga: TachiyomiXManga

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            MangaCoverImage(url: manga.thumbnailURL, title: manga.title)
                .aspectRatio(2.0 / 3.0, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            Text(manga.title)
                .font(.caption)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Renders the filter list the source described, and reports changes back.
struct SourceFilterSheet: View {
    let filters: [SourceFilter]
    @Binding var states: [Int: SourceFilterState]
    let onApply: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(filters) { filter in
                row(filter)
            }
        }
        .navigationTitle("Filters")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("Reset") { states.removeAll() }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Apply") {
                    onApply()
                    dismiss()
                }
            }
        }
    }

    /// Returns AnyView rather than `some View`: the group case renders its children through this
    /// same function, and a self-referential opaque type cannot be inferred.
    private func row(_ filter: SourceFilter) -> AnyView {
        switch filter.kind {
        case .header:
            return AnyView(Text(filter.name).font(.subheadline.weight(.semibold)))
        case .separator:
            return AnyView(Divider())
        case .text:
            return AnyView(TextField(filter.name, text: Binding(
                get: {
                    if case let .text(value) = states[filter.index] ?? .text("") { return value }
                    return ""
                },
                set: { states[filter.index] = .text($0) }
            )))
        case .checkbox:
            return AnyView(Toggle(filter.name, isOn: Binding(
                get: {
                    if case let .checkbox(value) = states[filter.index] ?? .checkbox(false) { return value }
                    return false
                },
                set: { states[filter.index] = .checkbox($0) }
            )))
        case .tristate:
            return AnyView(Button {
                let current: Int
                if case let .tristate(value) = states[filter.index] ?? .tristate(0) {
                    current = value
                } else {
                    current = 0
                }
                states[filter.index] = .tristate((current + 1) % 3)
            } label: {
                HStack {
                    Text(filter.name)
                    Spacer()
                    Image(systemName: tristateIcon(filter))
                        .foregroundStyle(tristateColor(filter))
                }
            }
            .buttonStyle(.plain))
        case let .select(options), let .sort(options):
            return AnyView(Picker(filter.name, selection: Binding(
                get: {
                    switch states[filter.index] {
                    case let .select(index): return index
                    case let .sort(index, _): return index
                    default: return 0
                    }
                },
                set: { newValue in
                    if case .sort = filter.kind {
                        states[filter.index] = .sort(index: newValue, ascending: false)
                    } else {
                        states[filter.index] = .select(newValue)
                    }
                }
            )) {
                ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                    Text(option).tag(index)
                }
            })
        case let .group(children):
            return AnyView(Section(filter.name) {
                ForEach(children) { child in
                    row(child)
                }
            })
        }
    }

    private func tristateIcon(_ filter: SourceFilter) -> String {
        guard case let .tristate(value) = states[filter.index] ?? .tristate(0) else { return "square" }
        switch value {
        case 1: return "checkmark.square.fill"
        case 2: return "xmark.square.fill"
        default: return "square"
        }
    }

    private func tristateColor(_ filter: SourceFilter) -> Color {
        guard case let .tristate(value) = states[filter.index] ?? .tristate(0) else { return .secondary }
        switch value {
        case 1: return .accentColor
        case 2: return .red
        default: return .secondary
        }
    }
}

/// Serialises filter states into the `filterStates` payload the host expects.
enum SourceFilterEncoder {
    static func encode(states: [Int: SourceFilterState], filters: [SourceFilter]) -> String? {
        guard !states.isEmpty else { return nil }
        let payload: [[String: Any]] = states.compactMap { index, state in
            switch state {
            case let .text(value):
                guard !value.isEmpty else { return nil }
                return ["index": index, "type": "text", "value": value]
            case let .checkbox(value):
                guard value else { return nil }
                return ["index": index, "type": "checkbox", "value": value]
            case let .tristate(value):
                guard value != 0 else { return nil }
                return ["index": index, "type": "tristate", "value": value]
            case let .select(value):
                return ["index": index, "type": "select", "value": value]
            case let .sort(index: selected, ascending: ascending):
                return [
                    "index": index,
                    "type": "sort",
                    "value": selected,
                    "ascending": ascending
                ]
            }
        }
        guard !payload.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
