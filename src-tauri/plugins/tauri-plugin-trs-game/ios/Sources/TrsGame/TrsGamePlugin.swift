// TRS Launcher – iOS-Teil des Spiel-Plugins `trs-game`. Befehle (vom Rust-Teil, src/ios/tauri_bridge.rs):
//   probe   → Gerätedaten (JIT, Speicher, Bildschirm, Pfade)
//   launch  → Spielansicht zeigen, ggf. auf JIT warten, JVM starten; Ereignisse über `events`
//   runJava, stop → auf iOS nicht möglich (siehe unten)
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

import Tauri
import UIKit
import WebKit

struct LaunchArgs: Decodable {
  let request: LaunchRequest
  let events: Channel
}

class TrsGamePlugin: Plugin {
  @objc public func probe(_ invoke: Invoke) {
    DispatchQueue.main.async {
      let window = self.manager.viewController?.view.window
      invoke.resolve(DeviceProbe.collect(window: window))
    }
  }

  @objc public func launch(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(LaunchArgs.self)
    guard let engine = Engine.shared else {
      invoke.reject("Spiel-Engine fehlt in diesem Build", code: "engineMissing")
      return
    }
    DispatchQueue.main.async {
      if engine.used || GameSession.current != nil {
        invoke.reject("Engine läuft schon", code: "restartRequired")
        return
      }
      guard let presenter = self.topViewController() else {
        invoke.reject("Keine Ansicht zum Anzeigen", code: "platform")
        return
      }
      let session = GameSession(request: args.request, events: args.events)
      GameSession.current = session
      let game = GameViewController(engine: engine, session: session)
      presenter.present(game, animated: true) {
        invoke.resolve()
      }
    }
  }

  /// Eine zweite, kopflose JVM gibt es auf iOS nicht (kein Kindprozess, eine JVM pro Prozess).
  @objc public func runJava(_ invoke: Invoke) {
    invoke.reject("game.unsupportedPlatform", code: "unsupportedPlatform")
  }

  /// Die JVM läuft im App-Prozess: beenden hieße die App beenden – das geht nur im Spiel.
  @objc public func stop(_ invoke: Invoke) {
    invoke.reject("game.unsupportedPlatform", code: "unsupportedPlatform")
  }

  private func topViewController() -> UIViewController? {
    var vc = manager.viewController
    while let presented = vc?.presentedViewController {
      vc = presented
    }
    return vc
  }
}

@_cdecl("init_plugin_trs_game")
func initPlugin() -> Plugin {
  return TrsGamePlugin()
}
