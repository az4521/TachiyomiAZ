# Manga details screen — not built yet

The reader, settings, downloads and logging all compile against the shared database. This screen
does not, and it needs more than facade methods:

- **The tracking protocol.** `MangaDetailsHeaderView` and `MangaView+ViewModel` call
  `TrackerManager.shared.isTracking`, `getTracker(id:)` and three sync entry points, and expect
  upstream's richer `Tracker` (icons, score options, `register`). See `../Tracking/README.md` —
  the same blocker parks `TrackerSearchView` and `SettingsTrackingView`.
- **The Browse/Source UI**, not yet vendored: `MangaListViewController`,
  `CategorySelectViewController`, `TrackerModalViewController`.
- **Two facade methods** that need a home for data Android's schema has no column for:
  `hasEditedKey` (which fields the user has overridden) and `setCover` (a user-chosen cover).
- **Two SwiftUI expressions** the type checker times out on (`MangaView.swift:222`,
  `MangaCoverPageView.swift:94`), which need splitting.

Most of the facade work this screen needs is already done — `ChapterObject`, `cacheMangaSummaries`,
`cacheMangaDetails`, `getMangaChapterFilters`, `updateMangaDetails`, `setOpened` and
`MangaManager.getNextChapter` all landed while chasing it. What is left is the tracking pass and the
Browse area, in that order.
