# Aidoku's pre-runner source screens — superseded

These drive Aidoku's original WASM `Source` class: `source.manifest`, `settingItems`,
`getMangaListing`, `getDefaultFilters`. `ExtensionRunner.Source` has none of that, and this port has
no legacy sources at all — every source is a JVM extension.

The screens actually used are `Vendored/Source/` (`NewSourceViewController`,
`SourceListingViewController`, the filter sheet), which upstream also uses for runner-backed
sources. The two call sites that reached these were both guarded by `legacySource`, which is nil
here, so those branches were dead.
