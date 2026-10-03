// TRS Launcher – Gerätedaten für den Rust-Teil (src/ios/probe.rs, gleiche Feldnamen).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

import UIKit

struct DeviceProbe: Encodable {
  struct Jit: Encodable {
    let enabled: Bool
    let csDebugged: Bool
    let flags: UInt32
    let debuggerAttached: Bool
  }

  struct Entitlements: Encodable {
    let getTaskAllow: Bool
    let increasedMemoryLimit: Bool
    let extendedVirtualAddressing: Bool
  }

  struct Memory: Encodable {
    let physicalMb: UInt64
    let availableMb: UInt64
    let maxContiguousMb: UInt64
  }

  struct Screen: Encodable {
    let widthPx: UInt32
    let heightPx: UInt32
    let scale: Double
    let maxFps: UInt32
  }

  let engineInstalled: Bool
  let engineUsed: Bool
  let jit: Jit
  let entitlements: Entitlements
  let memory: Memory
  let osVersion: String
  let screen: Screen
  /// Links, oben, rechts, unten in Pixeln – fürs Querformat umgerechnet.
  let safeInsetsPx: [UInt32]
  let bundlePath: String
  let homeDir: String
  let timeZone: String

  static func documentsPath() -> String {
    let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Documents")
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url.path
  }

  /// Muss auf dem Haupt-Thread laufen (UIScreen/UIWindow).
  static func collect(window: UIWindow?) -> DeviceProbe {
    let engine = Engine.shared
    let screen = window?.screen ?? UIScreen.main
    let scale = screen.scale
    let size = screen.bounds.size
    let physicalMb = ProcessInfo.processInfo.physicalMemory >> 20
    return DeviceProbe(
      engineInstalled: engine != nil,
      engineUsed: engine?.used ?? false,
      jit: Jit(
        enabled: engine?.jitEnabled ?? false,
        csDebugged: engine?.csDebugged ?? false,
        flags: engine?.jitFlags ?? 0,
        debuggerAttached: engine?.debuggerAttached ?? false),
      entitlements: Entitlements(
        getTaskAllow: engine?.hasEntitlement("get-task-allow") ?? false,
        increasedMemoryLimit: engine?.hasEntitlement("com.apple.developer.kernel.increased-memory-limit") ?? false,
        extendedVirtualAddressing: engine?.hasEntitlement("com.apple.developer.kernel.extended-virtual-addressing") ?? false),
      memory: Memory(
        physicalMb: physicalMb,
        availableMb: engine?.availableMemoryMb ?? 0,
        maxContiguousMb: engine?.maxContiguousMb(limit: min(physicalMb, 16384)) ?? 0),
      osVersion: UIDevice.current.systemVersion,
      screen: Screen(
        widthPx: UInt32(max(0, (size.width * scale).rounded())),
        heightPx: UInt32(max(0, (size.height * scale).rounded())),
        scale: Double(scale),
        maxFps: UInt32(max(30, screen.maximumFramesPerSecond))),
      safeInsetsPx: landscapeInsets(window: window, scale: scale),
      bundlePath: Bundle.main.bundlePath,
      homeDir: documentsPath(),
      timeZone: TimeZone.current.identifier)
  }

  /// Das Spiel läuft quer; im Hochformat gemessene Ränder werden gedreht.
  private static func landscapeInsets(window: UIWindow?, scale: CGFloat) -> [UInt32] {
    let insets = window?.safeAreaInsets ?? .zero
    let bounds = window?.bounds.size ?? UIScreen.main.bounds.size
    let landscape: UIEdgeInsets
    if bounds.height > bounds.width {
      // iPhone mit Notch/Insel: oben → im Querformat links und rechts; Home-Balken bleibt unten.
      let hasHomeBar = insets.bottom > 0
      let isPhone = UIDevice.current.userInterfaceIdiom == .phone
      let side = isPhone && hasHomeBar ? insets.top : 0
      landscape = UIEdgeInsets(top: 0, left: side, bottom: hasHomeBar ? 21 : 0, right: side)
    } else {
      landscape = insets
    }
    return [landscape.left, landscape.top, landscape.right, landscape.bottom].map { UInt32(max(0, ($0 * scale).rounded())) }
  }
}
