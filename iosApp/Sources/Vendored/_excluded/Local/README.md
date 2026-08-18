# Local file sources — not built yet

Importing CBZ/EPUB files as a local source is a feature of its own, and its index —
`LocalFileDataManager` — is written against a CoreData entity (`LocalFileInfoObject`) with no
counterpart in the shared schema. Porting it means choosing where that index should live, which is
a decision worth making deliberately rather than as a side effect of getting downloads to build.

Kept here so the port is a diff away rather than a rewrite. What is still compiled is
`../Local/LocalFileManager.swift`, which the download subsystem needs for its file-extension sets
and its CBZ page reader.
