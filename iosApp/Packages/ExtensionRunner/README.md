# TachiyomiAZRunner

This is TachiyomiAZ's in-repository source-model and runner-protocol module.
Its product keeps the module name `ExtensionRunner` so the GPL Aidoku application
code does not need a risky mass rename.

It is not the source-available ExtensionRunner project and does not contain that
project's interpreter, source loader, Wasm3 integration, or implementation
code. AIX/WASM construction fails explicitly. Online sources on iOS are
implemented by TachiyomiAZ's JVM extension host.

The compatibility surface follows Mihon's source model: search, filters,
popular/browse and latest listings, manga details, chapters, and pages. It does
not expose Aidoku's component-based Home API.
