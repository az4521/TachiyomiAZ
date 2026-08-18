// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "ExtensionRunner",
    platforms: [
        .iOS(.v15),
        .macOS(.v12)
    ],
    products: [
        .library(name: "ExtensionRunner", targets: ["ExtensionRunner"])
    ],
    // Wasm3 was declared for Aidoku's legacy WASM source path. This port only uses the JVM
    // path, and Runner.swift's Interpreter is already a stub that constructs nothing -- "Wasm3"
    // survives in that file only as a comment. Dropping the dependency keeps the package
    // buildable here without fetching a package nothing imports.
    targets: [
        .target(name: "ExtensionRunner")
    ]
)
