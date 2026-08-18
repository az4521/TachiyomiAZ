import SwiftUI
import TachiyomiKit

/// Category management: create, rename, reorder, delete.
///
/// Straight onto `CategoryQueries` in `:core-database` -- insertCategory, deleteCategory and the
/// rest are shared default methods, so a category made here is a category the Android app sees.
struct CategoriesView: View {
    @EnvironmentObject private var library: LibraryStore

    @State private var newName = ""
    @State private var showingAdd = false
    @State private var renaming: MangaCategory?
    @State private var renameText = ""

    var body: some View {
        List {
            Section {
                if library.categories.isEmpty {
                    Text("No categories.").foregroundStyle(.secondary)
                } else {
                    ForEach(library.categories, id: \.name) { category in
                        HStack {
                            Text(category.name)
                            Spacer()
                            Text("\(count(of: category))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture {
                            renameText = category.name
                            renaming = category
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) {
                                Task { await library.deleteCategory(category) }
                            }
                        }
                    }
                }
            } header: {
                Text("Categories")
            } footer: {
                Text("Tap to rename. Entries in a deleted category return to the default one.")
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showingAdd = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add category")
            }
        }
        .alert("New category", isPresented: $showingAdd) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) { newName = "" }
            Button("Create") {
                let name = newName
                newName = ""
                Task { await library.addCategory(named: name) }
            }
        }
        .alert("Rename category", isPresented: Binding(
            get: { renaming != nil },
            set: { if !$0 { renaming = nil } }
        )) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) { renaming = nil }
            Button("Rename") {
                if let category = renaming {
                    let name = renameText
                    Task { await library.renameCategory(category, to: name) }
                }
                renaming = nil
            }
        }
        .task { await library.reload() }
    }

    private func count(of category: MangaCategory) -> Int {
        guard let id = category.id?.int32Value else { return 0 }
        return library.manga.filter { $0.category == id }.count
    }
}
