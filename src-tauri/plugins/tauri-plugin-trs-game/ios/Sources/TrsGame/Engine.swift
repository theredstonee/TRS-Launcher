// TRS Launcher – Bindung an libtrsengine.dylib (C-Schnittstelle: Engine/trs/trs_engine.h).
// Zur Laufzeit geladen: fehlt die Engine (Launcher-Build ohne Spiel), baut und startet die App trotzdem.
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

import Foundation

final class Engine {
  typealias IntFn = @convention(c) () -> Int32
  typealias U32Fn = @convention(c) () -> UInt32
  typealias U64Fn = @convention(c) () -> UInt64
  typealias U64ArgFn = @convention(c) (UInt64) -> UInt64
  typealias StrIntFn = @convention(c) (UnsafePointer<CChar>?) -> Int32
  typealias VoidFn = @convention(c) () -> Void
  typealias LogCallback = @convention(c) (UnsafePointer<CChar>?) -> Void
  typealias CodeCallback = @convention(c) (Int32) -> Void
  typealias SetCallbacksFn = @convention(c) (LogCallback?, CodeCallback?, CodeCallback?, VoidFn?) -> Void
  typealias PtrFn = @convention(c) (UnsafeMutableRawPointer?) -> Void
  typealias SizeFn = @convention(c) (Int32, Int32, Int32, Int32) -> Void
  typealias LaunchFn = @convention(c) (
    UnsafePointer<CChar>?, Int32, Int32, UnsafePointer<CChar>?, UnsafePointer<CChar>?,
    Int32, UnsafePointer<UnsafePointer<CChar>?>?, Int32, UnsafePointer<UnsafePointer<CChar>?>?
  ) -> Int32
  typealias ErrorNameFn = @convention(c) (Int32) -> UnsafePointer<CChar>?
  typealias KeyFn = @convention(c) (Int32, Int32, Int32, Int32) -> Void
  typealias CharFn = @convention(c) (UInt32) -> Void
  typealias ButtonFn = @convention(c) (Int32, Int32) -> Void
  typealias FloatsFn = @convention(c) (Float, Float) -> Void

  static let apiVersion: Int32 = 1
  /// `nil`, wenn die Engine nicht in der App liegt oder nicht passt.
  static let shared: Engine? = Engine()

  private let setCallbacksFn: SetCallbacksFn
  private let jitEnabledFn: IntFn
  private let csDebuggedFn: IntFn
  private let jitFlagsFn: U32Fn
  private let debuggerAttachedFn: IntFn
  private let entitlementFn: StrIntFn
  private let availableMemoryFn: U64Fn
  private let maxContiguousFn: U64ArgFn
  private let usedFn: IntFn
  private let setSurfaceFn: PtrFn
  private let setWindowSizeFn: SizeFn
  private let launchFn: LaunchFn
  private let errorNameFn: ErrorNameFn
  private let finishExitFn: VoidFn
  private let keyFn: KeyFn
  private let charFn: CharFn
  private let buttonFn: ButtonFn
  private let moveRelativeFn: FloatsFn
  private let moveAbsoluteFn: FloatsFn
  private let scrollFn: FloatsFn
  private let grabbingFn: IntFn
  private let pauseFn: VoidFn

  private init?() {
    guard let dir = Bundle.main.privateFrameworksPath else { return nil }
    let path = (dir as NSString).appendingPathComponent("libtrsengine.dylib")
    guard FileManager.default.fileExists(atPath: path) else { return nil }
    guard let handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL) else {
      if let error = dlerror() {
        NSLog("[TRSGame] libtrsengine nicht geladen: %@", String(cString: error))
      }
      return nil
    }
    func load<T>(_ name: String, _ type: T.Type) -> T? {
      guard let symbol = dlsym(handle, name) else {
        NSLog("[TRSGame] Symbol fehlt: %@", name)
        return nil
      }
      return unsafeBitCast(symbol, to: type)
    }
    guard let version = load("trs_engine_api_version", IntFn.self), version() == Engine.apiVersion,
      let setCallbacks = load("trs_engine_set_callbacks", SetCallbacksFn.self),
      let jitEnabled = load("trs_engine_jit_enabled", IntFn.self),
      let csDebugged = load("trs_engine_cs_debugged", IntFn.self),
      let jitFlags = load("trs_engine_jit_flags", U32Fn.self),
      let debuggerAttached = load("trs_engine_debugger_attached", IntFn.self),
      let entitlement = load("trs_engine_entitlement", StrIntFn.self),
      let availableMemory = load("trs_engine_available_memory_mb", U64Fn.self),
      let maxContiguous = load("trs_engine_max_contiguous_mb", U64ArgFn.self),
      let used = load("trs_engine_used", IntFn.self),
      let setSurface = load("trs_engine_set_surface", PtrFn.self),
      let setWindowSize = load("trs_engine_set_window_size", SizeFn.self),
      let launch = load("trs_engine_launch", LaunchFn.self),
      let errorName = load("trs_engine_error_name", ErrorNameFn.self),
      let finishExit = load("trs_engine_finish_exit", VoidFn.self),
      let key = load("trs_input_key", KeyFn.self),
      let char = load("trs_input_char", CharFn.self),
      let button = load("trs_input_mouse_button", ButtonFn.self),
      let moveRelative = load("trs_input_mouse_move_relative", FloatsFn.self),
      let moveAbsolute = load("trs_input_mouse_move_absolute", FloatsFn.self),
      let scroll = load("trs_input_scroll", FloatsFn.self),
      let grabbing = load("trs_input_is_grabbing", IntFn.self),
      let pause = load("trs_engine_pause_if_needed", VoidFn.self)
    else {
      return nil
    }
    setCallbacksFn = setCallbacks
    jitEnabledFn = jitEnabled
    csDebuggedFn = csDebugged
    jitFlagsFn = jitFlags
    debuggerAttachedFn = debuggerAttached
    entitlementFn = entitlement
    availableMemoryFn = availableMemory
    maxContiguousFn = maxContiguous
    usedFn = used
    setSurfaceFn = setSurface
    setWindowSizeFn = setWindowSize
    launchFn = launch
    errorNameFn = errorName
    finishExitFn = finishExit
    keyFn = key
    charFn = char
    buttonFn = button
    moveRelativeFn = moveRelative
    moveAbsoluteFn = moveAbsolute
    scrollFn = scroll
    grabbingFn = grabbing
    pauseFn = pause
  }

  // MARK: Geräteprüfung

  var jitEnabled: Bool { jitEnabledFn() != 0 }
  var csDebugged: Bool { csDebuggedFn() != 0 }
  var jitFlags: UInt32 { jitFlagsFn() }
  var debuggerAttached: Bool { debuggerAttachedFn() != 0 }
  var used: Bool { usedFn() != 0 }
  var availableMemoryMb: UInt64 { availableMemoryFn() }

  func hasEntitlement(_ key: String) -> Bool {
    key.withCString { entitlementFn($0) != 0 }
  }

  func maxContiguousMb(limit: UInt64) -> UInt64 {
    maxContiguousFn(limit)
  }

  // MARK: Start

  func setCallbacks(log: LogCallback?, exit: CodeCallback?, grab: CodeCallback?, firstFrame: VoidFn?) {
    setCallbacksFn(log, exit, grab, firstFrame)
  }

  func setSurface(_ view: AnyObject?) {
    setSurfaceFn(view.map { Unmanaged.passUnretained($0).toOpaque() })
  }

  func setWindowSize(physicalWidth: Int, physicalHeight: Int, width: Int, height: Int) {
    setWindowSizeFn(Int32(physicalWidth), Int32(physicalHeight), Int32(width), Int32(height))
  }

  /// Startet die JVM. `nil` = läuft, sonst Fehlername (z. B. `jitRequired`).
  func launch(_ request: LaunchRequest) -> String? {
    let env = request.env.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }
    let home = request.env["POJAV_HOME"] ?? DeviceProbe.documentsPath()
    let launch = launchFn
    let code: Int32 = request.javaHome.withCString { javaHome in
      home.withCString { homeDir in
        request.session.withCString { session in
          Engine.withCStrings(request.argv) { argv in
            Engine.withCStrings(env) { envp in
              launch(
                javaHome, Int32(request.javaMajor), Int32(request.xmxMb), homeDir, session,
                Int32(request.argv.count), argv, Int32(env.count), envp)
            }
          }
        }
      }
    }
    if code == 0 {
      return nil
    }
    return errorNameFn(code).map { String(cString: $0) } ?? "unknown"
  }

  func finishExit() {
    finishExitFn()
  }

  /// C-Zeichenketten für die Dauer des Aufrufs (die Engine kopiert sie).
  private static func withCStrings<R>(_ strings: [String], _ body: (UnsafePointer<UnsafePointer<CChar>?>?) -> R) -> R {
    let copies: [UnsafeMutablePointer<CChar>?] = strings.map { strdup($0) }
    defer { copies.forEach { free($0) } }
    let pointers: [UnsafePointer<CChar>?] = copies.map { $0.map { UnsafePointer($0) } }
    return pointers.withUnsafeBufferPointer { body($0.baseAddress) }
  }

  // MARK: Eingabe (Spielpixel)

  func key(_ key: Int, scancode: Int, down: Bool, mods: Int) {
    keyFn(Int32(key), Int32(scancode), down ? 1 : 0, Int32(mods))
  }

  func char(_ codepoint: UInt32) {
    charFn(codepoint)
  }

  func mouseButton(_ button: Int, down: Bool) {
    buttonFn(Int32(button), down ? 1 : 0)
  }

  func moveRelative(dx: Float, dy: Float) {
    moveRelativeFn(dx, dy)
  }

  func moveAbsolute(x: Float, y: Float) {
    moveAbsoluteFn(x, y)
  }

  func scroll(dx: Float, dy: Float) {
    scrollFn(dx, dy)
  }

  var isGrabbing: Bool { grabbingFn() != 0 }

  func pauseIfNeeded() {
    pauseFn()
  }
}
