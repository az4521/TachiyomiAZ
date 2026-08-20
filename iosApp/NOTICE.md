# Licensing and attribution — iOS app

The iOS app is licensed under the **GNU General Public License, version 3**
([`iosApp/LICENSE`](LICENSE)), and not under the Apache License 2.0 that covers
the rest of this repository.

It has to be. Most of this app is Aidoku, which is GPL-3.0, and the GPL requires
that a work containing it be distributed under the same terms. That obligation
applies to the built app as well as to the source: anyone given a copy of the IPA
is entitled to the corresponding source under GPL-3.0.

## What comes from Aidoku

Everything under [`Sources/Vendored/`](Sources/Vendored) — 325 files, roughly
60,000 lines, which is most of the app by volume. The reader, the library screen,
the manga details screen, settings, tracking, and the local storage around them
are Aidoku's, and the file headers name their authors.

- **Aidoku** — <https://github.com/Aidoku/Aidoku>, GPL-3.0, © Skitty and Aidoku
  contributors. The upstream app.
- **tachiyomiazios** — <https://github.com/az4521/tachiyomiazios>, GPL-3.0. A fork
  of Aidoku, and what was vendored here directly. `Scripts/build-openjdk-ios15.sh`
  and `Scripts/patches/openjdk-mobile-ios-runtime.patch` also come from it.

Changes made here are described in the file headers and in the commit history.

## What does not come from Aidoku

The rest of the repository is Apache-2.0 and stays that way:

- `app/` — the Android app, derived from Tachiyomi (Apache-2.0).
- `core-model/`, `core-database/`, `core-domain/`, `shared-ios/` — the shared
  Kotlin Multiplatform modules, written for this project.
- `Runtime/` — the JVM extension host and its shims.

The iOS app links the shared Kotlin modules as `TachiyomiKit`. That direction is
fine: Apache-2.0 is compatible with GPL-3.0, so Apache-2.0 code may be used within
a GPL-3.0 work. The reverse is not true, which is why the vendored Aidoku code
cannot be carried under this repository's root licence.

## Third-party components

Fetched at build time rather than vendored, under their own licences:

Licences below were read from each package's own licence file in the resolved
checkout, except the last three, which are noted as unverified because they
arrive as binaries or as a build of another project's source.

| Component | Licence | Used for |
| --- | --- | --- |
| [Nuke / NukeUI](https://github.com/kean/Nuke) | MIT | Cover loading and the disk image cache |
| [Gifu](https://github.com/kaishin/Gifu) | MIT | Animated covers |
| [SwiftSoup](https://github.com/scinfu/SwiftSoup) | MIT | HTML parsing |
| [ZIPFoundation](https://github.com/weichsel/ZIPFoundation) | MIT | CBZ and backup archives |
| [MarkdownUI](https://github.com/gonzalezreal/swift-markdown-ui) | MIT | Rendering release notes |
| [NetworkImage](https://github.com/gonzalezreal/NetworkImage) | MIT | Pulled in by MarkdownUI |
| [swift-cmark](https://github.com/swiftlang/swift-cmark) | MIT | Pulled in by MarkdownUI |
| [SwiftUIIntrospect](https://github.com/siteline/swiftui-introspect) | MIT | UIKit access from SwiftUI |
| [Texture / AsyncDisplayKit](https://github.com/Skittyblock/Texture) | Apache-2.0 upstream (unverified — fetched as a prebuilt xcframework carrying no licence file) | Reader scrolling |
| [OpenJDK Mobile](https://github.com/openjdk-mobile/ios-tools) | GPL-2.0 with Classpath Exception (unverified locally — built from source by CI) | The JVM that runs Tachiyomi extensions |
| [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) (AndroidCompat) | MPL-2.0 (unverified locally — cloned and built by a script) | The Android compatibility layer extensions run against |

The Classpath Exception on OpenJDK is what permits linking it into this app
without its own terms reaching the rest of the code.

Tachiyomi extensions themselves are neither bundled nor distributed here; they are
downloaded by the user at runtime from repositories the user configures.
