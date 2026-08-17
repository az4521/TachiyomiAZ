# Picking this up on macOS

Written on the Windows machine where the work was done, for the first session on
a Mac. Branch `rxjava-migration`, at `0e0b912421`.

```bash
git clone https://github.com/az4521/TachiyomiAZ.git
cd TachiyomiAZ
git checkout rxjava-migration
```

Do not copy the Windows working tree across. It carries two workarounds you no
longer need: the build mirror under `Documents/programming/tachiyomiaz`, which
existed only because Gradle cannot build on the SMB share the repo lives on, and
the `verify.sh` that syncs into it. On the Mac, build in the repo.

## The one thing that has never been done anywhere

**The iOS binaries have never been linked.** Not once, on any machine.

```bash
./gradlew linkDebugFrameworkIosSimulatorArm64
```

Run this first. Expect it to fail, and treat the failure as the point — it is
the only remaining unknown, and nobody has seen its output yet.

What is already known to be fine: the shared source type-checks for iOS.
`compileIosMainKotlinMetadata` resolves `commonMain` and `iosMain` against the
real shared API and passes for all three modules. What linking additionally
exercises is native *dependency artifacts* — `kotlinx-datetime` 0.6.2,
`kotlinx-serialization-protobuf` 1.11.0, `kotlinx-coroutines` 1.11.0, and
SQLDelight 2.0.2's `native-driver`. Any of those could lack a native variant at
the pinned version. `IosDatabaseFactory` also gets compiled for real for the
first time.

## State

Three KMP modules, 88 files, 62 tests.

| Module | Contents |
|---|---|
| `:core-model` | source models, database models, `Page`, `ChapterRecognition` |
| `:core-database` | SQLDelight queries, mappers, tables, `DatabaseHandler` |
| `:core-domain` | chapter sync, chapter filter/sort, manga update rules, library update selection, migration, backup format and merges |

```bash
./gradlew checkSharedPortability      # metadata compile + tests, ~7s, no Apple toolchain
./gradlew assembleStandardApi24Debug  # full APK, R8 included
```

Both pass on Windows. Worth running both on the Mac early, to confirm nothing
was accidentally host-dependent.

Schema ownership is deliberately asymmetric and this is the part to not
disturb: on Android `DbOpenCallback` still owns creation and all 18 upgrade
steps against real user databases, and SQLDelight is handed an already-open
helper. On iOS `Database.Schema` owns the database outright, via
`IosDatabaseFactory`. Both read the same `.sq` files.

## Next steps after linking

1. An umbrella module exporting one XCFramework across the three. The iOS repo's
   `nightly.yml` already builds Gradle on `macos-26` with JDK 21, so wiring it in
   is a step in an existing pipeline rather than new infrastructure.
2. SKIE or KMP-NativeCoroutines for the Swift boundary — `suspend` becomes
   completion handlers and `Flow` needs a wrapper, and `DatabaseHandler` exposes
   plenty of both.
3. Swift opens a database through `IosDatabaseFactory` and reads the library.
4. Restore an Android backup on iOS. Same schema, same `@ProtoNumber` wire
   format, no conversion layer. That is the demonstration worth having.

The shared `Source` interface is deliberately not built: every extracted rule
takes its platform work as an injected interface, so nothing needs it yet. Its
shape becomes obvious once "fetch details and chapters, then sync" is what is
being shared.

## Two traps, both already paid for

**A JVM target is not a portability check.** `checkSharedPortability` originally
compiled the shared modules for plain JVM, which shares almost everything with
Android, so JVM-only API in `commonMain` passed straight through it. Two things
did: `javaClass` in four `equals()` implementations, and `Dispatchers.IO`, which
coroutines declares in its JVM and Native source sets but not in the common API.
The guard runs the metadata compilations now. Do not weaken it back.

**Rx operators encoded concurrency, and converting them lost it.** Every runtime
bug found while testing this branch was the same shape — correct data, wrong
timing or ordering:

| Site | Rx encoded | Converted to | Effect |
|---|---|---|---|
| Backup restore | `.subscribe()` ran synchronously | `launchIO` | progress raced ahead of the work; writes escaped their transaction |
| Downloads | `flatMap` streamed pages | a `List` | resolve-then-download in two phases; long silent start |
| Reader | `BehaviorRelay` re-emits equal values | `StateFlow` | conflated; the next chapter never loaded |

`RXJAVA_MIGRATION.md` §5 predicted this exactly: *"the data is correct, the UI
stops being notified. Unit tests will not catch it."* It was right. If something
misbehaves on iOS, look for the same shape before suspecting the logic.

## Related

`SHARED_CODE_PLAN.md` in the `tachiaz-ios` repo is current at revision 3 and
covers the architecture and what deliberately stays JVM-side. `PORTING_PLAN.md`
in that repo predates the KMP decision and has never been reviewed — treat it as
suspect until it has been.
