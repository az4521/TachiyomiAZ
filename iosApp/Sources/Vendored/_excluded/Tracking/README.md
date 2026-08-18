# Enhanced trackers (Komga, Kavita, Suwayomi) — not built yet

These three track against the same server a manga came from, so their clients are built on that
source's own helper *and* its response models — `KomgaBook`, `KomgaPageResponse`, `KavitaVolume`,
`SuwayomiModels`, and each source's `…SourceRunner`. In tachiyomiazios those come from the built-in
sources; here the sources are JVM extensions, so none of that exists.

Writing a small helper against the three members the tracker APIs use (`getServerUrl`,
`getAuthorizationHeader`, `request`) was not enough: the APIs also decode the sources' model types
and reach for their runners.

`Sources/Data/EnhancedSourceBridge.swift` is the part that is done, and it is the interesting part:
it reads a JVM extension's configured server and credentials out of the VM and mirrors them to the
keys these helpers expect. Restoring these three means porting each source's response models and
replacing the `…SourceRunner` lookups, with the bridge already supplying the connection details.

The other eight trackers build and are registered.
