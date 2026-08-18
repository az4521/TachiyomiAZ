# Aidoku's built-in sources — not applicable here

Komga, Kavita and Suwayomi are sources Aidoku ships itself, configured through these setup screens.
Every source in this port is a JVM extension installed from a repository, so there is nothing for
them to configure.

`LocalFileImportView` and `LocalSetupView` import CBZ/EPUB files as a local source, which is parked
separately — see `../Local/README.md`.

## The add-source screen

`AddSourceView` and its supporting cells install sources from Aidoku's "source lists" *inside the
Browse tab*. This port keeps extension installation on its own screen — `ExtensionsView`, backed by
`RepositoryStore` and `ExtensionCatalog` — which is where it was asked to live. `SourceListsView` is
parked for the same reason.

`OIDCLoginController` is the login flow for the self-hosted sources above.

## The Browse tab itself

`BrowseViewController` and `BrowseViewModel` present Aidoku's *source lists* — user-added URLs
serving downloadable sources, versioned with `SemanticVersion` and installed inline. This port gets
sources from extension repositories instead, so the Browse tab shows the installed sources
(`SourcesView`) and installing lives on its own Extensions tab, which is where it was asked to be.
