// TRS Launcher – Eingabe-Schnittstelle der Spiel-Engine (Vertrag, gleich wie Kotlin `GameInput`).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

import Foundation

/// Das Touch-Overlay spricht nur hiermit. Tasten = GLFW-Codes, Koordinaten in Punkten der Spielansicht.
public protocol GameInput: AnyObject {
  func sendKey(glfwKey: Int, scancode: Int, down: Bool, mods: Int)
  func sendChar(codepoint: Int)
  /// 0 = links, 1 = rechts, 2 = Mitte.
  func sendMouseButton(button: Int, down: Bool)
  func moveMouseRelative(dx: Float, dy: Float)
  func moveMouseAbsolute(x: Float, y: Float)
  func scroll(dx: Float, dy: Float)
  /// Maus gefangen = im Spiel (sonst Menü).
  func isGrabbed() -> Bool
  func showKeyboard(show: Bool)
}

extension Notification.Name {
  /// Wird gesendet, wenn das Spiel die Maus fängt/freigibt (`object` = `GameInput`).
  public static let trsGameGrabChanged = Notification.Name("TRSGameGrabChanged")
}
