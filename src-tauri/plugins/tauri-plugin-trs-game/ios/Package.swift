// swift-tools-version:5.3
// TRS Launcher – Spiel-Engine für iOS (Amethyst-iOS). Wird vom iOS-Teil gefüllt.

import PackageDescription

let package = Package(
  name: "tauri-plugin-trs-game",
  platforms: [
    .iOS(.v14),
  ],
  products: [
    .library(
      name: "tauri-plugin-trs-game",
      type: .static,
      targets: ["tauri-plugin-trs-game"])
  ],
  dependencies: [
    .package(name: "Tauri", path: "../.tauri/tauri-api")
  ],
  targets: [
    .target(
      name: "tauri-plugin-trs-game",
      dependencies: [
        .byName(name: "Tauri")
      ],
      path: "Sources")
  ]
)
