# Vendored UI from tachiyomiazios — migration in progress

These files are **in-tree but not compiled** (`project.yml` excludes `Vendored/**`). The app builds
and runs without them; re-including them is how the migration continues.

## Why this exists

Hand-built screens were coming out unusable next to tachiyomiazios's mature UI. Taking that UI is
viable because its coupling turned out to be shallow — it binds to about six model types, not to
Aidoku's logic:

| | |
|---|---|
| `iOS/New` SwiftUI UI | 134 files, 27.6k loc, **no** AsyncDisplayKit |
| Files touching `NSManagedObject` directly | 29 |
| `ExtensionRunner` (was `AidokuRunner`) | 9 files, 1.3k loc, **no** dependencies after dropping vestigial Wasm3 |

The rule this migration follows: **take the UI, and nothing else.** Persistence, caching and source
access go to the KMP modules, so the shared database stays the single source of truth. Adopting
Aidoku's Core Data layer would give a working app that quietly abandons the point of the port.

## Done

- `ExtensionRunner` package vendored and renamed from `AidokuRunner` — that name described Aidoku's
  WASM runner, which tachiyomiazios had already replaced with a JVM one, so it named nothing that
  remained. Its vestigial Wasm3 dependency was dropped: `Interpreter` is already a stub and "Wasm3"
  survives only in a comment.
- `Common/`, `Utilities/`, `Extensions/`, and two view-model types vendored (~7k loc).
- Third-party packages matched to the fork's versions: Nuke 13 (also the image cache), Gifu,
  SwiftUIIntrospect, SwiftSoup.
- KMP-backed shims written for what the UI expects: `MangaManager` (library operations through
  `IosDatabaseHandler`) and `SourceManager` (source lookup through `SourceRuntime`).

## Remaining, in order

1. `Common/Settings/SettingView.swift` — references `TachiyomiXSourceRunner` for per-source
   settings, and `AppDelegate.presentAlert`. Needs the source-settings path rebuilt on
   `SourceRuntime` plus a small alert helper.
2. A `CoreDataManager`-shaped adapter over the shared queries, for the 54 files that call it. The
   API is per-entity (Chapter, Category, History, LibraryManga, Track, Manga) and maps closely onto
   `:core-database`'s query mixins.
3. The 29 files holding `NSManagedObject` directly — real adaptation, not a shim.
4. Then the feature areas, cheapest first: Library (2 files, 0 CoreData), Reader (1 file, 0),
   Browse, Manga, History, Settings (24 files — Backups, Categories, Downloads, Insights/stats,
   About, Tracking).

## Deliberate omissions, already made

- `MangaCollectionViewController.swift` (in `_excluded/`) — extends AsyncDisplayKit's view
  controller; a large framework for one legacy screen the SwiftUI layer does not need.
- `SourceWebBrowserView` / `SourceWebBrowserPresenter`, removed from `Common/Settings/WebView.swift`
  — took `TachiyomiXSourceRunner`. The per-source login browser needs rebuilding on `SourceRuntime`.
- `JVMImageURLProtocol` in `Common/SourceImageView.swift` — routed cover requests back through the
  JVM for per-source headers. Without it, sources gating covers on a referer or Cloudflare cookie
  will show broken covers. Browsing and reading are unaffected.
