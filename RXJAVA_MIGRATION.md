# Migrating TachiyomiAZ off RxJava

Status: RxJava mostly done; storio replaced by SQLDelight
Last updated: 2026-08-15
Branch: `rxjava-migration`

**RxJava**: 103 files at the start, 33 now. Five of those must keep it
permanently as the extension shim (`Source.kt`, `CatalogueSource.kt`,
`HttpSource.kt`, `network/OkHttpExtensions.kt`, `util/lang/RxCoroutineBridge.kt`),
and 18 are the `exh`/EH subsystem, which is fork-only and shares `RxUtil` and
`DelegatedHttpSource` so it converts as one unit or not at all.

**storio**: gone. All seven tables plus the three exh metadata tables are on
SQLDelight, the dependency is out of `app/build.gradle`, and the 14 resolvers,
6 type mappings, `DbExtensions` and exh's `DatabaseExtensions` are deleted.

**Nucleus and Conductor stay.** They are Android UI libraries, and the iOS app
has its own UI, so presenters are never shared and neither library affects what
can compile for Kotlin/Native. Removing them would be a lifecycle refactor, not
a modernisation one. Nucleus sits in the same "boundary library" tier as
ReactiveNetwork and the extension `Source` API.

## Database notes for whoever works here next

- `DbOpenCallback` still owns schema creation and all 18 upgrade steps. The
  `.sq` `CREATE TABLE` statements exist only so SQLDelight can type the
  queries; it is handed an `AndroidSqliteDriver` over the already-open helper
  and must never be switched to `Database.Schema`, which would try to create
  tables that already exist on real installs.
- storio and SQLDelight shared one open helper during the migration, which is
  what let call sites move one at a time without splitting transactions.
- `DatabaseHelper.inTransaction` is `inline` on purpose: callers invoke suspend
  functions inside it, and SQLDelight's `transaction` takes a non-inline lambda.
- Two escape hatches remain for things a typed layer cannot express:
  `executeSQL` for schema-era `EXHMigrations` statements, and `rawQueryIds` for
  the exh search engine, which composes SQL at runtime.
- `mangas.sq` deliberately omits `unread` and `category`. `MangaTable` declares
  them as if they were columns, but they are aliases computed by the library
  query.
- SQLDelight's default dialect predates `ON CONFLICT DO UPDATE`, so upserts are
  written as update-then-insert-if-no-rows-changed.

## Bugs found and fixed while porting

- `getMergedChaptersQuery` interpolated `$(Merged.COL_MERGE_ID}` -- a paren
  instead of a brace -- so Kotlin emitted it as literal text and the query was
  invalid SQL at runtime.
- `BrowseSourcePresenter` delivered page errors through
  `view().subscribe { ... }`, which stays subscribed and re-fires on every
  later view emission.
- `Bangumi.bind` built an `add(track)` Observable and dropped it without
  subscribing, so the add never ran.
- `getRecentManga` interpolated the search term and offset straight into SQL;
  they are bind parameters now.

## Still to do

- `exh`/EH off RxJava (18 files), or leave it -- the library stays on the
  classpath for extensions either way.
- OkHttp -> Ktor for trackers only, never for sources.
- Extract a `:core` KMP module: models, chapter recognition, backup schema,
  sort/filter predicates, preference keys.

Sources cannot go to KMP: `HttpSource` exposes OkHttp `Request`/`Response`,
jsoup `Document` and `Observable`, and every third-party extension links
against exactly those types.

## Build

Gradle cannot build on `Z:` -- it is an SMB share and Gradle's
create-directory-then-stat pattern fails against SMB metadata caching. Mirror
the tree to local disk and build there; `JAVA_HOME` also points at a Java 8 JDK
while AGP 8.13 needs 17+, so export it explicitly.


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

### Prerequisite: the build does not work from `Z:`

Two environment problems block compile verification, and both cost real time to
rediscover:

**`Z:` is an SMB share** (`\\192.168.0.150\az4521`). Gradle creates a directory
and immediately stats it; SMB metadata caching makes that fail intermittently
(`Cannot create directory 'buildSrc\build\tmp\jar'`, `Failed to create MD5 hash
... as it does not exist`). `--no-daemon`, `--no-parallel`, `--no-build-cache`
and disabling VFS watching do not fix it. Build from local disk instead: clone
or copy the tree to a local path, sync sources into it, and compile there. A
shallow clone is ~14 MB; `local.properties` and `app/google-services.json` are
gitignored and must be recreated.

**`JAVA_HOME` points at a Java 8 JDK** (`scoop/apps/temurin8-jdk`), while AGP
8.13 requires 17+. Gradle uses `JAVA_HOME`, not `PATH`, so the JDK 21 on `PATH`
is not picked up. Export `JAVA_HOME` explicitly for the build.

With both handled, `:app:compileStandardApi24DebugKotlin` is clean on `master`
and takes ~2 minutes.

### Phase 0 — infrastructure — DONE (`736a70cc6c`)

What was actually there differed from the description above, which assumed the
scoped helpers had to be written. They already existed:

**Two copies of `CoroutinesExtensions.kt`** declared the same top-level
`launchUI`/`launchIO`/`launchNow` in different packages — `util/lang` (the
`GlobalScope` versions only) and `util/system` (the same three, plus the
`CoroutineScope`-scoped variants and `withUIContext`/`withIOContext`/
`withDefContext`). Which one a call site got depended purely on its import, and
all 24 call sites had imported the `util/lang` copy, leaving the scoped variants
dead code. The `util/lang` copy is now deleted and its imports repointed.

**The `GlobalScope` helpers stay for now.** Upstream kept them through this
batch and added the scoped alternatives alongside, letting call sites migrate
one at a time. Removing them wholesale means touching all 24 sites at once, for
no benefit over doing it per-phase.

`presenterScope` is on `BasePresenter`, cancelled in `onDestroy`. Note the
deviation from upstream `86b9d7e843`: it declares the scope `lateinit` and
assigns it *inside* the `try`, after `super.onCreate()` — the call the
surrounding `catch` exists to swallow. When that NPE fires, the `lateinit` is
never initialized and `onDestroy` throws. Initialize eagerly at construction
instead.

`RxCoroutineBridge.kt` stays. It is the migration tool and the permanent
extension shim; it goes last, and partly never.

### Phase 1 — trackers — DONE (`92f7ee0b71`, `024925d41d`)

`data/track` is free of RxJava. Notes below kept as the record of what the
conversion involved.


Calling this "leaves" was wrong. `TrackService` declares `add`, `update`,
`bind`, `search`, `refresh` (all `Observable`) and `login` (`Completable`) as
**abstract**, so there is no incremental path: converting any one of them
converts all 9 implementations and every call site in the same commit.

Call sites outside `data/track/`, all of which must move together:

| Site | Handling |
|---|---|
| `TrackPresenter` (5 calls, incl. a `.toBlocking().first()`) | convert with the presenter |
| `AbstractBackupRestore.kt:104` | already in a coroutine |
| `LibraryUpdateService.kt:470` | already in a coroutine |
| `ReaderPresenter.kt:669` | wrap in `runAsObservable` — defer to Phase 4 |
| `TrackChapterSync.kt:43` | wrap in `runAsObservable` — defer to Phase 4 |

`runAsObservable` (`RxCoroutineBridge.kt:245`) is what keeps this from
cascading into the reader. Note its documented `Dispatchers.IO` default: with
`Unconfined`, chains resume on OkHttp callback threads and can exhaust
`maxRequestsPerHost`. Do not "simplify" that away.

**Hidden predecessor.** The trackers split in two:

- *Already suspend underneath*, wrapped in `runAsObservable` — Hikka, Kavita,
  Komga, MangaBaka. Converting these is unwrapping.
- *Still genuinely Rx underneath* — Anilist, Bangumi, Kitsu, whose `api.*`
  methods return `Observable` (Anilist's `update` is a real `flatMap` chain).
  These need their API classes converted first, which upstream did in **December
  2020**, before `7d713b87b1`: `271de31d51` (Kitsu → coroutines +
  kotlinx.serialization), `dc3ed7fffc` (Anilist), `6fcf6ae1f5` (Bangumi and
  Shikimori), `ea33179a95` (add/update/login), `2d0a5eb02c` (more of
  `TrackService`), plus `1268caf3e0` on OkHttp error semantics.

So `7d713b87b1` alone is not the reference for this phase — it is the last
commit of a chain, and this fork starts before the chain begins.

Also in scope: `network/OkHttpExtensions.kt` (`Call.asObservable()` →
`Call.await()`), which the API conversions sit on.

### Phase 2 — data layer — PARTIALLY DONE

**Done:**

- backup/restore (`c6c5f1a164`, reference `990fb22d3e`)
- `LibraryUpdateService` (`954333b83b`, reference `86b9d7e843`)

**Blocked on Phases 3/4 — the phases are not independent.** The remaining
data-layer items all *publish* Rx streams that presenters and the reader
consume, so they cannot convert before (or without) their consumers:

| Item | Consumers that must move with it |
|---|---|
| `DownloadQueue` (`getStatusObservable`, `getProgressObservable`, `getUpdatedObservable`) | `DownloadPresenter`, `ChaptersPresenter`, `UpdatesPresenter` |
| `Downloader` | shares `Page`'s status subject with `HttpPageLoader` (reader) |
| `ExtensionManager.installExtension` / `ExtensionInstaller` | `ExtensionPresenter` |

`Downloader` is the hardest single item in the whole migration and should be
done with the reader, not here. Its pipeline is
`downloadsRelay.concatMapIterable → groupBy(source) → concatMap → downloadChapter`,
with `flatMap(..., 5)` bounding page concurrency. A coroutine version needs an
explicit `Channel` plus a `Semaphore`, and it has to preserve both the
per-source serialisation and the 5-page cap. Getting either wrong degrades
silently — stalled queues or a source being hammered — rather than crashing.

**Still genuinely independent and not yet done:** the 38 `asRx*` DB call sites.
These are mostly inside presenters, so they fall out of Phase 3 naturally.

### Phase 3 — presenters — LARGELY DONE

Converted: Category, Repo, Source, History, ExtensionDetails, MangaInfo, Video,
Migration, Track, Download, Updates, Browse, GlobalSearch, Library, Extension,
Chapters (partially), plus MangaController, MigrationController,
MigrationListController and the settings controllers.

**Delivery helpers added to `BasePresenter`**, because dropping Nucleus's
`deliver*` transformers naively loses behaviour that matters:

| Helper | Replaces | Semantics |
|---|---|---|
| `collectLatestCache` | `subscribeLatestCache` | holds the newest value while detached, drops superseded ones |
| `collectReplay` | `subscribeReplay` | delivers every value in order, for incrementally built lists |
| `deliverToView` | one-shot `subscribeFirst` | runs once, when a view is attached |

`RxController` and `SettingsController` gained `collectUntilDestroy` /
`collectUntilDetach` next to the Rx pair, backed by scopes with matching
lifetimes.

Two traps worth knowing before continuing:

- A trailing lambda on `collectLatestCache` binds to `onError`, not `onNext`,
  because `onError` is the last parameter. Pass `onNext` by name.
- `MutableStateFlow(Unit)` never emits on re-set: StateFlow conflates equal
  values. `BehaviorRelay<Unit>` triggers must become counters (LibraryPresenter)
  or `MutableSharedFlow`.

Relays converted so far map as: `BehaviorRelay` -> `MutableSharedFlow(replay = 1)`
(or `MutableStateFlow`), `PublishRelay` -> `MutableSharedFlow` with no replay,
`onBackpressureBuffer` -> `extraBufferCapacity`.

### Phase 4 — the reader — DONE

`Page.setStatusSubject` became `setStatusFlow`. This turned out to be
app-internal plumbing — only `HttpPageLoader` and `DownloadQueue` ever called
it — so `Page` left the "must keep RxJava" list without changing anything
extensions touch (constructor, `status`, `progress`, `uri`).

`PageLoader`'s contract is now `suspend fun getPages(): List<ReaderPage>` and
`fun getPage(page): Flow<Int>`; all seven loaders follow.

`HttpPageLoader` kept its structure on purpose:

- each worker still owns a single-thread executor, now through
  `asCoroutineDispatcher`. `queue.take()` parks the thread while the queue is
  empty, so it must not move onto a shared pool — the existing comment about
  OkHttp dispatcher threads applies unchanged;
- `getPage` is a `channelFlow` whose `awaitClose` does what `doOnUnsubscribe`
  did: drop still-queued pages nobody is watching, so workers don't fetch pages
  that have scrolled away;
- the priority queue and preload sizing are untouched.

The page holders' 100 ms progress pollers stayed polling — `Page.progress` has
no change notification — but the loop only advances after the view update runs,
which is what `onBackpressureLatest` was guarding against. Image-header streams
stay open with `awaitCancellation` and close in a `finally`, replacing
`Observable.never` + `doOnUnsubscribe`.

### Next: the Downloader

`downloadsRelay.concatMapIterable → groupBy(source) → flatMap(..., 5) { bySource.concatMap { downloadChapter } }`
means **at most 5 sources downloading at once, sequential within a source**, and
`downloadChapter` itself has a second `flatMap(..., 5)` capping concurrent page
downloads.

A faithful coroutine version needs a per-source `Mutex` (serialisation) plus a
`Semaphore(5)` (source cap), taking the mutex *before* the permit so a waiting
download does not hold a permit. The inner page cap is a second `Semaphore(5)`.
Both caps must survive: losing the source cap hammers sites, losing the
per-source ordering interleaves chapters.

`DownloadService.runningRelay` is an rxrelay `BehaviorRelay` read by
`DownloadController`; it converts with the Downloader.

### Remaining work

**The reader (18 files) is the largest and least referenced chunk.**
`ReaderPresenter`, `PagerPageHolder`, `WebtoonPageHolder`, `HttpPageLoader`,
`ChapterLoader` and the page loaders. Upstream only covers these post-Compose,
against `ReaderViewModel`. `HttpPageLoader` is the subtle one: a priority queue
with Rx backpressure bounding page preloading, needing an explicit `Channel`
plus `Semaphore`. It shares `Page`'s status subject with `Downloader`, so those
two convert together.

**`Downloader` (4 files with `data/download`)** — still the hardest single item;
see the Phase 2 notes above. Its `groupBy(source) → concatMap` per-source
serialisation and `flatMap(..., 5)` page cap must both survive.

**`source/online/` (6 fork-specific files)** — `EHentai`, `NHentai`,
`HentaiCafe`, `Pururin`, `Tsumino`, `LewdSource`, plus `HttpSourceFetcher` and
`SourceManager`. `EHentai.fetchChapterList` is bridged with `awaitSingle` from
the backup code today.

**`exh/` (6 files)** — `RxUtil`, `EHentaiUpdateHelper`, and friends. No upstream
reference at all. Leaving these on RxJava indefinitely remains legitimate.

**Base classes last** — `BasePresenter` and `RxController` keep their Rx halves
until every subclass is converted; `BasePresenter` cannot stop extending
`RxPresenter` until Nucleus goes.

### Revised phase order

Phases 2, 3 and 4 interleave. The workable order is:

1. Presenters that only consume DB observables (Phase 3, easy half)
2. `DownloadQueue` + its three presenters, together
3. `ExtensionManager`/`ExtensionInstaller` + `ExtensionPresenter`, together
4. Reader + `Downloader` + `HttpPageLoader`, together (the hard one)
5. `exh/`
6. Cleanup

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
