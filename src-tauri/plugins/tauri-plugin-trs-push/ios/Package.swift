// swift-tools-version:5.3
// TRS Launcher – Push-Benachrichtigungen auf iOS: Abholen im Hintergrund (BGAppRefreshTask) + lokale Hinweise.

import PackageDescription

let package = Package(
  name: "tauri-plugin-trs-push",
  platforms: [
    .iOS(.v14),
  ],
  products: [
    .library(
      name: "tauri-plugin-trs-push",
      type: .static,
      targets: ["tauri-plugin-trs-push"])
  ],
  dependencies: [
    .package(name: "Tauri", path: "../.tauri/tauri-api")
  ],
  targets: [
    .target(
      name: "tauri-plugin-trs-push",
      dependencies: [
        .byName(name: "Tauri")
      ],
      path: "Sources")
  ]
)
