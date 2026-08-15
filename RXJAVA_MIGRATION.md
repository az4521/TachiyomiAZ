# Migrating TachiyomiAZ off RxJava

Status: proposal
Last updated: 2026-08-15

## 1. The goal has to be restated

**RxJava cannot leave the APK.** Extensions compile against it. `Source.kt`
still exposes `fetchMangaDetails`/`fetchChapterList`/`fetchPageList` returning
`Observable`, deprecated but live, and every extensions-lib 1.4/1.5 extension
calls them. Dropping `io.reactivex:rxjava:1.3.8` from `app/build.gradle` means
`NoClassDefFoundError` on most of the ecosystem.

The achievable goal is: **remove RxJava from application code, keep the library
on the classpath as an extension-compatibility shim.**

The win is not APK size. It is deleting the dual concurrency model, retiring
Nucleus, and getting off `GlobalScope`.

## 2. State of play — further along than it looks

The hard part is already done. `Source.kt` has suspend functions as primary and
Rx as the deprecated fallback, bridged by `awaitSingle()`. The extension-facing
boundary is migrated.

What's left is internal:

| Metric | Count |
|---|---|
| Files importing `rx.*` | 103 |
| — in `ui/` | 57 |
| — in `data/` | 30 |
| — in `source/` | 12 |
| — in `exh/` | 6 |
| Files already importing `kotlinx.coroutines` | 87 |
| `asRxObservable`/`asRxSingle`/`asRxCompletable` call sites | 38 |
| `executeAsBlocking` call sites | 204 |
| Presenter classes | 25 |
| Nucleus import lines (whole app) | 6 |

Two things stand out:

- **storio is not the blocker.** Queries return `PreparedOperation`; the Rx-ness
  is at the call site. 204 sites already use `executeAsBlocking` against 38 Rx
  ones. There is no need to touch storio, and no need to migrate to SQLDelight.
- **Nucleus is barely present.** Six import lines, all in `ui/base/`. The 25
  presenters depend on the `subscribeFirst`/`subscribeLatestCache` helpers
  defined in `BasePresenter`, not on Nucleus directly.

Also: the local `coroutine-1.5` branch (`f75d0eeb33`, "half broken coroutine
garbage", 2025-03-02) migrated `Source`/`CatalogueSource`/`HttpSource`. Master
has since gone further via the tachiyomix 1.6 work. The branch is superseded —
delete it rather than trying to merge it.

## 3. What can actually be taken from upstream

All the relevant history is already in this repo via the `dupstream` and
`mstream` remotes. But it splits into two eras, and only one is usable.

**The boundary is April–June 2022.** Compose landed starting `c475acd1ea`
(2022-04-17) and SQLDelight starting `b1f46ed830` (2022-04-21), finishing around
`e3b1053c03` (2022-06-22). Everything after that references `ReaderViewModel`,
`DownloadQueueScreenModel`, and Voyager screen models — architecture this fork
does not have and should not adopt just to complete this migration.

### Usable: the January 2021 batch

Written against the same storio + Nucleus + Conductor architecture this fork
still runs. File paths still match.

| Commit | Date | Scope |
|---|---|---|
| `7d713b87b1` | 2021-01-03 | Remove RxJava from tracker classes (7 files) |
| `7eb0868791` | 2021-01-04 | `TrackPresenter` (92 lines) |
| `ac9bf1f3ff` | 2021-01-04 | `MangaPresenter` bridged calls |
| `5cfda1b1bf` | 2021-01-04 | `GlobalSearchPresenter` + migration `SearchPresenter` |
| `990fb22d3e` | 2021-01-04 | backup/restore (7 files) |
| `2c9f8bb9ce` | 2021-01-04 | **Revert of a bad conversion in the above** |
| `86b9d7e843` | 2021-01-23 | `LibraryUpdateService` (259 lines) |

`2c9f8bb9ce` fixes a regression introduced by that same batch — tracking data
stopped updating in the UI. Take it together with the rest, and treat it as a
warning about what to test.

### Reference-only: 2022-07 onward

Read for approach; do not expect the diffs to apply.

`788583e66f` (2022-07-10), `bb1e7816e1` + `beda99bbe0` (reader, 2022-12-02),
`2245658363` (2023-01-09), `62480f090b` (`ChapterLoader`, 2023-01-14),
`e4bc8990fb` (`HttpPageLoader`, 2023-01-21), `bd2cb97179` (`DownloadQueue`,
2023-02-07), `ffa8c8fd07` (`PageHolder`, 2023-02-18), `fa61c8fe6f`
(2023-02-21), `3ae1e37c40` (`Downloader`, 2023-05-24), `0ac38297f4` (extension
installer, 2023-05-30).

### Cherry-pick will not work

This fork diverged from upstream at `80fd49d60b` (2017-11-28) — over three years
before even the January 2021 batch, plus `exh/`, merged manga, and saved
searches on top. Read these as reference diffs (`git show <sha>`) and reapply by
hand. Budget for that; do not plan around `git cherry-pick`.

## 4. Phases

### Phase 0 — infrastructure

`CoroutinesExtensions.kt` currently builds `launchUI`, `launchIO`, and
`launchNow` on **`GlobalScope`**. Every coroutine already in the codebase — 87
files' worth — outlives the component that started it. Fix this first or the
migration multiplies an existing leak.

- Replace the `GlobalScope` helpers with scoped equivalents.
- Add `presenterScope` to `BasePresenter`, cancelled in `onDestroy`.
- Keep `RxCoroutineBridge.kt`. It is the migration tool and the permanent
  extension shim; it goes last, and partly never.

### Phase 1 — leaves

No internal dependents, independently testable.

- `network/OkHttpExtensions.kt`: `Call.asObservable()` → `Call.await()`.
- Trackers: `MyAnimeListApi` (25 Rx references), `KitsuApi` (20),
  `MangaUpdates` (17), and the rest. Reference `7d713b87b1`.

### Phase 2 — data layer

- 38 `asRx*` call sites → `executeAsBlocking()` inside `withIOContext`, or Flow
  where the call site genuinely observes changes. storio stays.
- `FullBackupRestore.kt` (reference `990fb22d3e`).
- `LibraryUpdateService.kt` (reference `86b9d7e843`).
- `Downloader.kt` — no usable upstream reference; `3ae1e37c40` is post-Compose.

### Phase 3 — presenters

25 files, one per PR, each independently revertable. Roughly easy to hard:

`ExtensionPresenter` → `MangaInfoPresenter` → `TrackPresenter` (`7eb0868791`) →
`ChaptersPresenter` → `LibraryPresenter` → `GlobalSearchPresenter`
(`5cfda1b1bf`).

`GlobalSearchPresenter` is the genuinely hard one: parallel fan-out across every
source with results streaming in per-source as they arrive. This is the single
place where RxJava's `merge`/`flatMap` is actually elegant. `channelFlow` with
one `async` per source is the equivalent — write it deliberately rather than
mechanically.

Once all 25 are converted, `BasePresenter` stops extending `RxPresenter` and
Nucleus leaves the dependency list.

### Phase 4 — the reader

The expensive phase, and the one with no usable reference.

`ReaderPresenter` (58 Rx references), `WebtoonPageHolder` (43),
`PagerPageHolder` (41), `HttpPageLoader` (41). Four of the five heaviest Rx
files in the codebase, all in the reader, all covered upstream only by
post-Compose commits written against `ReaderViewModel`.

`HttpPageLoader` is the subtle one: it uses a priority queue with Rx
backpressure to keep page preloading bounded. The coroutine version needs an
explicit `Channel` plus a `Semaphore`. Getting this wrong degrades quietly —
pages load in the wrong order or preloading runs unbounded — rather than
crashing.

Do this phase last, on its own branch, with real reading sessions as the test.

### Phase 5 — exh/

`exh/util/RxUtil.kt` (22), `EHentaiUpdateHelper` (18), plus `EHentai.kt`,
`NHentai.kt`, `LewdSource.kt`. Fork-specific, zero upstream reference, well
isolated. Leaving these on RxJava indefinitely is a legitimate outcome — the
library is on the classpath regardless.

### Phase 6 — cleanup

Delete `RxExtensions.kt`. Keep `RxCoroutineBridge.kt` for the deprecated source
methods. Drop `rxandroid`, `rxrelay`, `nucleus`, `nucleus-support-v7`, and
`adapter-rxjava` from `app/build.gradle`. **Keep `io.reactivex:rxjava:1.3.8`.**

## 5. Testing

The January 2021 batch shipped a UI-update regression that needed `2c9f8bb9ce`
to fix, in tracking specifically. The failure mode for this whole migration is
the same shape: the data is correct, the UI stops being notified. Unit tests
will not catch it.

Per phase, manually verify: library updates refresh visible rows; tracker
changes appear without leaving the screen; backup restore reports progress and
completes; downloads queue, pause, and resume; the reader preloads ahead and
does not reorder pages.

## 6. Why this is worth doing

Beyond removing the dual concurrency model and the `GlobalScope` leak: RxJava 1
is JVM-only, and it is the main reason the shared-core options for the iOS port
are limited to JVM hosting. Coroutines are Kotlin/Native-compatible. Completing
at least Phases 1–3 makes the trackers, network layer, and backup code eligible
to compile natively for iOS rather than running in the 64MB Zero interpreter —
which is the constraint that makes JVM hosting unattractive for anything on a
frame budget.

Phases 1–3 are the ones that pay twice. Phase 4 pays once, in code health.
