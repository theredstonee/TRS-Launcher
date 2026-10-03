// Platzhalter: Der iOS-Teil (Amethyst-iOS) ersetzt diese Datei. Bis dahin melden
// alle Befehle "game.unsupportedPlatform" – die Rust-API bleibt dieselbe
// (Befehle: launch, runJava, stop; Kanal-Nachrichten wie auf Android).

import Tauri
import UIKit
import WebKit

class TrsGamePlugin: Plugin {
  @objc public func launch(_ invoke: Invoke) {
    invoke.reject("game.unsupportedPlatform")
  }

  @objc public func runJava(_ invoke: Invoke) {
    invoke.reject("game.unsupportedPlatform")
  }

  @objc public func stop(_ invoke: Invoke) {
    invoke.resolve()
  }
}

@_cdecl("init_plugin_trs_game")
func initPlugin() -> Plugin {
  return TrsGamePlugin()
}
