# Excluded from the build

Files vendored from tachiyomiazios that are not compiled here, kept for reference.

- `MangaCollectionViewController.swift` — a UIKit collection controller extending
  `BaseCollectionViewController`, which derives from AsyncDisplayKit's `ASDKViewController`.
  Pulling that in would add a large UI framework for one legacy screen, and the SwiftUI layer
  this port is taking does not need it.

These are excluded by living outside the target's `Sources` group; see `project.yml`.
