# Tracker search — not built yet

`TrackerSearchView` is written against upstream's `Tracker` protocol, which is considerably richer
than this port's: it wants `id`, `name`, `icon`, `getTrackerInfo()`, `option(for:options:)`,
`register(tracker:manga:item:)`, and a `search(title:includeNsfw:)` returning `TrackSearchItem`.
This app implements a smaller protocol (see `Sources/Data/Tracking.swift`) covering MyAnimeList and
AniList, where the track rows themselves live in the shared `manga_sync` table.

Nothing references this view yet, so it is parked rather than half-adapted. Restoring it means
conforming the two trackers here to upstream's protocol — which would also bring back
`SettingsTrackingView`, parked for the same reason. That is a tracking pass of its own, not
something to settle while getting the reader to compile.
