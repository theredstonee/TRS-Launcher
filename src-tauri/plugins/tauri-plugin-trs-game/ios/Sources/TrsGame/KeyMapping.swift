// TRS Launcher – Hardware-Tastatur (HID) → GLFW-Tastencodes.
// Die Zahlen in `GlfwKey` prüft tests/ios-keymap.test.ts gegen Amethysts glfw_keycodes.h.
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

import UIKit

enum GlfwKey {
  static let space = 32
  static let apostrophe = 39
  static let comma = 44
  static let minus = 45
  static let period = 46
  static let slash = 47
  static let num0 = 48
  static let semicolon = 59
  static let equal = 61
  static let a = 65
  static let leftBracket = 91
  static let backslash = 92
  static let rightBracket = 93
  static let graveAccent = 96
  static let world1 = 161
  static let world2 = 162
  static let escape = 256
  static let enter = 257
  static let tab = 258
  static let backspace = 259
  static let insert = 260
  static let delete = 261
  static let right = 262
  static let left = 263
  static let down = 264
  static let up = 265
  static let pageUp = 266
  static let pageDown = 267
  static let home = 268
  static let end = 269
  static let capsLock = 280
  static let scrollLock = 281
  static let numLock = 282
  static let printScreen = 283
  static let pause = 284
  static let f1 = 290
  static let kp0 = 320
  static let kpDecimal = 330
  static let kpDivide = 331
  static let kpMultiply = 332
  static let kpSubtract = 333
  static let kpAdd = 334
  static let kpEnter = 335
  static let kpEqual = 336
  static let leftShift = 340
  static let leftControl = 341
  static let leftAlt = 342
  static let leftSuper = 343
  static let rightShift = 344
  static let rightControl = 345
  static let rightAlt = 346
  static let rightSuper = 347
  static let menu = 348

  static let modShift = 0x0001
  static let modControl = 0x0002
  static let modAlt = 0x0004
  static let modSuper = 0x0008
  static let modCapsLock = 0x0010
}

@available(iOS 13.4, *)
enum KeyMapping {
  static func glfwKey(_ usage: UIKeyboardHIDUsage) -> Int? {
    let raw = usage.rawValue
    // A–Z und 1–9, 0 liegen in HID und GLFW jeweils am Stück.
    if raw >= UIKeyboardHIDUsage.keyboardA.rawValue && raw <= UIKeyboardHIDUsage.keyboardZ.rawValue {
      return GlfwKey.a + (raw - UIKeyboardHIDUsage.keyboardA.rawValue)
    }
    if raw >= UIKeyboardHIDUsage.keyboard1.rawValue && raw <= UIKeyboardHIDUsage.keyboard9.rawValue {
      return GlfwKey.num0 + 1 + (raw - UIKeyboardHIDUsage.keyboard1.rawValue)
    }
    if raw >= UIKeyboardHIDUsage.keyboardF1.rawValue && raw <= UIKeyboardHIDUsage.keyboardF12.rawValue {
      return GlfwKey.f1 + (raw - UIKeyboardHIDUsage.keyboardF1.rawValue)
    }
    if raw >= UIKeyboardHIDUsage.keypad1.rawValue && raw <= UIKeyboardHIDUsage.keypad9.rawValue {
      return GlfwKey.kp0 + 1 + (raw - UIKeyboardHIDUsage.keypad1.rawValue)
    }
    switch usage {
    case .keyboard0: return GlfwKey.num0
    case .keypad0: return GlfwKey.kp0
    case .keyboardReturnOrEnter: return GlfwKey.enter
    case .keyboardEscape: return GlfwKey.escape
    case .keyboardDeleteOrBackspace: return GlfwKey.backspace
    case .keyboardTab: return GlfwKey.tab
    case .keyboardSpacebar: return GlfwKey.space
    case .keyboardHyphen: return GlfwKey.minus
    case .keyboardEqualSign: return GlfwKey.equal
    case .keyboardOpenBracket: return GlfwKey.leftBracket
    case .keyboardCloseBracket: return GlfwKey.rightBracket
    case .keyboardBackslash, .keyboardNonUSPound: return GlfwKey.backslash
    case .keyboardSemicolon: return GlfwKey.semicolon
    case .keyboardQuote: return GlfwKey.apostrophe
    case .keyboardGraveAccentAndTilde: return GlfwKey.graveAccent
    case .keyboardComma: return GlfwKey.comma
    case .keyboardPeriod: return GlfwKey.period
    case .keyboardSlash: return GlfwKey.slash
    case .keyboardCapsLock: return GlfwKey.capsLock
    case .keyboardPrintScreen: return GlfwKey.printScreen
    case .keyboardScrollLock: return GlfwKey.scrollLock
    case .keyboardPause: return GlfwKey.pause
    case .keyboardInsert: return GlfwKey.insert
    case .keyboardHome: return GlfwKey.home
    case .keyboardPageUp: return GlfwKey.pageUp
    case .keyboardDeleteForward: return GlfwKey.delete
    case .keyboardEnd: return GlfwKey.end
    case .keyboardPageDown: return GlfwKey.pageDown
    case .keyboardRightArrow: return GlfwKey.right
    case .keyboardLeftArrow: return GlfwKey.left
    case .keyboardDownArrow: return GlfwKey.down
    case .keyboardUpArrow: return GlfwKey.up
    case .keypadNumLock: return GlfwKey.numLock
    case .keypadSlash: return GlfwKey.kpDivide
    case .keypadAsterisk: return GlfwKey.kpMultiply
    case .keypadHyphen: return GlfwKey.kpSubtract
    case .keypadPlus: return GlfwKey.kpAdd
    case .keypadEnter: return GlfwKey.kpEnter
    case .keypadPeriod: return GlfwKey.kpDecimal
    case .keypadEqualSign: return GlfwKey.kpEqual
    case .keyboardNonUSBackslash: return GlfwKey.world2
    case .keyboardApplication: return GlfwKey.menu
    case .keyboardLeftControl: return GlfwKey.leftControl
    case .keyboardLeftShift: return GlfwKey.leftShift
    case .keyboardLeftAlt: return GlfwKey.leftAlt
    case .keyboardLeftGUI: return GlfwKey.leftSuper
    case .keyboardRightControl: return GlfwKey.rightControl
    case .keyboardRightShift: return GlfwKey.rightShift
    case .keyboardRightAlt: return GlfwKey.rightAlt
    case .keyboardRightGUI: return GlfwKey.rightSuper
    default: return nil
    }
  }

  static func glfwMods(_ flags: UIKeyModifierFlags) -> Int {
    var mods = 0
    if flags.contains(.shift) { mods |= GlfwKey.modShift }
    if flags.contains(.control) { mods |= GlfwKey.modControl }
    if flags.contains(.alternate) { mods |= GlfwKey.modAlt }
    if flags.contains(.command) { mods |= GlfwKey.modSuper }
    if flags.contains(.alphaShift) { mods |= GlfwKey.modCapsLock }
    return mods
  }
}
