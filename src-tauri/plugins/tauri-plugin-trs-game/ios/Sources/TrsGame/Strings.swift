// TRS Launcher – Texte der nativen Spielansicht (Deutsch/Englisch).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

import Foundation

enum Strings {
  static let german = Locale.preferredLanguages.first?.lowercased().hasPrefix("de") ?? false

  private static func t(_ de: String, _ en: String) -> String {
    german ? de : en
  }

  static var jitTitle: String { t("JIT ist nicht aktiv", "JIT is not enabled") }

  static var jitIntro: String {
    t(
      "Ohne JIT läuft Java auf dem iPhone/iPad nur im Interpreter – Minecraft wäre unspielbar langsam. Deshalb startet TRS das Spiel erst, wenn JIT an ist. Diese Seite prüft das jede Sekunde und startet dann automatisch.",
      "Without JIT, Java on iPhone/iPad can only run in an interpreter – Minecraft would be unplayably slow. TRS therefore only starts the game once JIT is enabled. This screen checks every second and starts automatically.")
  }

  static var jitStepsGeneric: String {
    t(
      """
      So aktivierst du JIT (eine Möglichkeit reicht):

      • SideStore + StikDebug (iOS 17.4 und neuer): StikDebug öffnen, „Connect by App“ wählen und TRS Launcher antippen. Danach hierher zurückkehren.

      • AltStore: AltServer auf deinem Mac/PC starten (gleiches WLAN oder Kabel), im AltServer-Menü „Enable JIT“ → dein Gerät → TRS Launcher.

      • TrollStore: TRS Launcher mit TrollStore installieren und dort „Open with JIT“ verwenden.

      • Jailbreak: JIT ist meist schon aktiv – App neu öffnen.

      JIT gilt nur, solange die App läuft. Nach einem Neustart der App musst du es erneut aktivieren.
      """,
      """
      How to enable JIT (one way is enough):

      • SideStore + StikDebug (iOS 17.4 and later): open StikDebug, choose “Connect by App” and tap TRS Launcher. Then come back here.

      • AltStore: run AltServer on your Mac/PC (same Wi-Fi or cable), then in the AltServer menu choose “Enable JIT” → your device → TRS Launcher.

      • TrollStore: install TRS Launcher with TrollStore and use “Open with JIT”.

      • Jailbreak: JIT is usually already enabled – reopen the app.

      JIT only lasts while the app is running. After restarting the app you need to enable it again.
      """)
  }

  static var jitStepsTxm: String {
    t(
      """
      Dein Gerät braucht unter iOS 26 einen dauerhaft verbundenen Debugger mit Skript:

      1. Öffne StikDebug und tippe lange auf TRS Launcher.
      2. Wähle „Assign Script“ und nimm „UniversalJIT26.js“ aus dem Ordner von TRS Launcher (Dateien-App → Auf meinem iPhone → TRS Launcher).
      3. Starte TRS Launcher über StikDebug („Connect by App“) und lass StikDebug verbunden.

      AltServer und TrollStore reichen auf diesen Geräten nicht.
      """,
      """
      On iOS 26 your device needs a debugger that stays attached, with a script:

      1. Open StikDebug and long-press TRS Launcher.
      2. Choose “Assign Script” and pick “UniversalJIT26.js” from the TRS Launcher folder (Files app → On My iPhone → TRS Launcher).
      3. Launch TRS Launcher through StikDebug (“Connect by App”) and keep StikDebug attached.

      AltServer and TrollStore are not enough on these devices.
      """)
  }

  static var jitWaiting: String { t("Warte auf JIT …", "Waiting for JIT …") }
  static var checkAgain: String { t("Erneut prüfen", "Check again") }
  static var cancel: String { t("Abbrechen", "Cancel") }
  static var close: String { t("Schließen", "Close") }
  static var back: String { t("Zurück", "Back") }
  static var copyLog: String { t("Log kopieren", "Copy log") }

  static var crashTitle: String { t("Minecraft wurde beendet", "Minecraft stopped") }

  static func crashMessage(code: Int) -> String {
    t(
      "Das Spiel ist mit Code \(code) abgestürzt. Auf iOS endet dabei auch die App – öffne TRS Launcher danach einfach wieder. Das Protokoll liegt in der Dateien-App unter TRS Launcher/trs-engine.log.",
      "The game crashed with code \(code). On iOS this also closes the app – just open TRS Launcher again afterwards. The log is in the Files app under TRS Launcher/trs-engine.log.")
  }

  static var startFailedTitle: String { t("Spiel konnte nicht starten", "Could not start the game") }

  static func startFailed(_ reason: String) -> String {
    switch reason {
    case "notEnoughMemory":
      return t(
        "Nicht genug zusammenhängender Speicher. Weniger Arbeitsspeicher im Profil einstellen oder die App mit dem Entitlement „Increased Memory Limit“ signieren.",
        "Not enough contiguous memory. Lower the memory in the profile or sign the app with the “Increased Memory Limit” entitlement.")
    case "runtimeBroken":
      return t(
        "Die Java-Laufzeit ließ sich nicht laden. Sie wird beim nächsten Start neu heruntergeladen.",
        "The Java runtime could not be loaded. It will be downloaded again on the next start.")
    case "restartRequired":
      return t(
        "Das Spiel lief in dieser Sitzung schon. Bitte TRS Launcher komplett schließen und neu öffnen.",
        "The game already ran in this session. Please close TRS Launcher completely and open it again.")
    default:
      return t("Interner Fehler (\(reason)).", "Internal error (\(reason)).")
    }
  }
}
