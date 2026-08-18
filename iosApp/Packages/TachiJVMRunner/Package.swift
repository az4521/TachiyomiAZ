// swift-tools-version: 5.10

import PackageDescription

let package = Package(
    name: "TachiJVMRunner",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(name: "TachiJVMRunner", targets: ["TachiJVMRunner"]),
    ],
    targets: [
        .target(
            name: "CJVMBridge",
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedLibrary("z"),
                .linkedFramework(
                    "CoreGraphics",
                    .when(platforms: [.iOS, .macOS])
                ),
                .linkedFramework(
                    "CoreText",
                    .when(platforms: [.iOS, .macOS])
                ),
                .linkedFramework(
                    "ImageIO",
                    .when(platforms: [.iOS, .macOS])
                ),
                .linkedFramework(
                    "JavaScriptCore",
                    .when(platforms: [.iOS, .macOS])
                ),
            ]
        ),
        .target(
            name: "TachiJVMRunner",
            dependencies: ["CJVMBridge"]
        ),
        .testTarget(
            name: "TachiJVMRunnerTests",
            dependencies: ["TachiJVMRunner"]
        ),
    ]
)
