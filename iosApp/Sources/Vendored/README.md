# Vendored UI

Source copied from [tachiyomiazios](https://github.com/az4521/tachiyomiazios) — the Aidoku fork —
and committed here rather than depended on as a library. Everything in this directory arrived from
that project; everything outside it is this port's own code.

Only one edit is applied wholesale: `AidokuRunner` → `ExtensionRunner`. That package had already
replaced Aidoku's WASM runner with one driving JVM extensions, so the Aidoku name described nothing
that remained.

**Keep changes here minimal.** Re-syncing with upstream is a diff against these files, and every
local edit makes that harder. Where the fork expects something this port spells differently, prefer
adapting *our* side: `Category.title` bridges to the shared model's `name`, and `DatabaseContainer`
mimics `NSPersistentContainer` so ~100 `performBackgroundTask` call sites need no edits at all.

## What is here

| Area | Notes |
| --- | --- |
| `Common/`, `Extensions/`, `Utilities/`, `Models/` | UI infrastructure — cells, modifiers, button styles, view models |
| `Models/Legacy/` | The fork's `Manga`/`Chapter` classes. These are the UI's currency; the shared KMP models are the database's. `ModelConversions.swift` is the one seam between them |
| `Settings/` | The multi-page settings tree (`Settings.swift`) and its pages |
| `Logging/` | Full logging subsystem, including the log viewer the settings page opens |
| `Downloads/` | Download manager, queue, tasks and cache (2,212 lines, verbatim) |
| `Backup/Models/` | Backup models with their CoreData halves stripped |
| `Manga/`, `Reader/`, `Browse/` | Manga details, the readers, source icons |

## What is deliberately not here

**Persistence.** The fork's CoreData stack is not vendored. `:core-database` is already the store and
the Android app reads it through the same generated queries, so a second stack would give the two
apps schemas that drift. `Sources/Data/SharedData/` contains only model/source-key translation and
small UI-facing adapters over shared KMP repositories; it owns no persistence state.

**The manager layer.** `MangaManager`, `HistoryManager` and `TrackerManager` drive chapter syncing
and library updates. This port takes those rules from `:core-domain` (`syncChaptersWithSource`,
`selectLibraryMangaToUpdate`) so both apps apply one rule rather than two implementations of it.

**Aidoku's built-in sources.** Komga, Kavita, Suwayomi and the local-file source are Aidoku
concepts; every source here is a JVM extension. `SourceListsView` went with them — extension
repositories are `RepositoryStore`'s job.

See `_excluded/` for code kept for reference but not compiled, and the README in each subdirectory
for why.
