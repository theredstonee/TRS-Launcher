// Touch-Steuerung: Layout-Format Version 1 – gleich wie trs_core::controls (Rust),
// app/utils/controls.ts und Layout.kt (Android). Positionen = Anteile (0..1) der
// sicheren Fläche (ohne Notch/Ränder).
import Foundation

/// Wohin das Overlay Eingaben schickt – gleiche Methoden wie `GameInput` der Engine
/// (GLFW-Codes). Eigenes Protokoll, damit die Logik ohne UIKit/Engine testbar bleibt.
protocol TouchInputSink: AnyObject {
    func sendKey(_ glfwKey: Int, scancode: Int, down: Bool, mods: Int)
    func sendChar(_ codepoint: Int)
    func sendMouseButton(_ button: Int, down: Bool)
    func moveMouseRelative(dx: Float, dy: Float)
    func moveMouseAbsolute(x: Float, y: Float)
    func scroll(dx: Float, dy: Float)
    /// Maus gefangen = im Spiel; sonst Menü mit Zeiger.
    func isGrabbed() -> Bool
    func showKeyboard(_ show: Bool)
}

enum TouchGlfw {
    static let keySpace = 32
    static let key1 = 49
    static let keyA = 65
    static let keyD = 68
    static let keyR = 82
    static let keyS = 83
    static let keyT = 84
    static let keyW = 87
    static let keyEscape = 256
    // Feste Tasten des TRS Clients im Touch-Modus (docs/touch-mode.md): TRS-Menü, Emote-Rad.
    static let keyF13 = 302
    static let keyF14 = 303
    static let keyLeftShift = 340
    static let keyLeftControl = 341
    static let keyLeftAlt = 342
    static let keyRightShift = 344
    static let keyRightControl = 345
    static let keyRightAlt = 346
    static let mouseLeft = 0
    static let mouseRight = 1
    static let mouseMiddle = 2
    static let modShift = 0x1
    static let modControl = 0x2
    static let modAlt = 0x4

    static func isValidKey(_ key: Int) -> Bool { (32...348).contains(key) }
}

enum TouchLimits {
    static let version = 1
    static let maxButtons = 48
    static let maxNameChars = 48
    static let maxLabelChars = 12
    static let maxChord = 3
    static let minSize: Float = 0.02
    static let minOpacity: Float = 0.05
    static let minSensitivity: Float = 0.1
    static let maxSensitivity: Float = 5
    static let eps: Float = 1e-6
}

enum TouchShape: String, Codable { case round, rect }

enum TouchShow: String, Codable { case game, menu, always }

enum TouchSpecial: String, Codable, CaseIterable {
    case keyboard, menu, trsMenu, emoteWheel, chat, hotbarSwipe, scrollUp, scrollDown
}

enum TouchAction: Equatable, Codable {
    /// Taste; `chord` = vorher gedrückt, danach losgelassen (z. B. F3 für F3+G).
    case key(Int, chord: [Int])
    case mouse(Int, chord: [Int])
    /// Taste einrasten: tippen = gedrückt, nochmal = los.
    case toggle(Int)
    case joystick(camera: Bool)
    case special(TouchSpecial)

    private enum K: String, CodingKey { case type, key, button, chord, mode, special }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        switch try c.decode(String.self, forKey: .type) {
        case "key": self = .key(try c.decode(Int.self, forKey: .key), chord: try c.decodeIfPresent([Int].self, forKey: .chord) ?? [])
        case "mouse": self = .mouse(try c.decode(Int.self, forKey: .button), chord: try c.decodeIfPresent([Int].self, forKey: .chord) ?? [])
        case "toggle": self = .toggle(try c.decode(Int.self, forKey: .key))
        case "joystick":
            switch try c.decode(String.self, forKey: .mode) {
            case "wasd": self = .joystick(camera: false)
            case "camera": self = .joystick(camera: true)
            default: throw TouchLayoutError.invalid("joystick")
            }
        case "special": self = .special(try c.decode(TouchSpecial.self, forKey: .special))
        default: throw TouchLayoutError.invalid("action")
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        switch self {
        case let .key(key, chord):
            try c.encode("key", forKey: .type)
            try c.encode(key, forKey: .key)
            if !chord.isEmpty { try c.encode(chord, forKey: .chord) }
        case let .mouse(button, chord):
            try c.encode("mouse", forKey: .type)
            try c.encode(button, forKey: .button)
            if !chord.isEmpty { try c.encode(chord, forKey: .chord) }
        case let .toggle(key):
            try c.encode("toggle", forKey: .type)
            try c.encode(key, forKey: .key)
        case let .joystick(camera):
            try c.encode("joystick", forKey: .type)
            try c.encode(camera ? "camera" : "wasd", forKey: .mode)
        case let .special(s):
            try c.encode("special", forKey: .type)
            try c.encode(s, forKey: .special)
        }
    }
}

enum TouchLayoutError: Error, Equatable { case invalid(String) }

struct TouchButton: Equatable, Codable {
    var id: String
    var label: String?
    var icon: String?
    var x: Float
    var y: Float
    var w: Float
    var h: Float
    var opacity: Float = 0.6
    var shape: TouchShape = .round
    var action: TouchAction
    var toggle: Bool = false
    var passThrough: Bool = false
    var show: TouchShow?

    init(id: String, label: String? = nil, icon: String? = nil, x: Float, y: Float, w: Float, h: Float, opacity: Float = 0.6,
         shape: TouchShape = .round, action: TouchAction, toggle: Bool = false, passThrough: Bool = false, show: TouchShow? = nil) {
        self.id = id
        self.label = label
        self.icon = icon
        self.x = x
        self.y = y
        self.w = w
        self.h = h
        self.opacity = opacity
        self.shape = shape
        self.action = action
        self.toggle = toggle
        self.passThrough = passThrough
        self.show = show
    }

    private enum K: String, CodingKey { case id, label, icon, x, y, w, h, opacity, shape, action, toggle, passThrough, show }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        id = try c.decode(String.self, forKey: .id)
        label = try c.decodeIfPresent(String.self, forKey: .label)
        icon = try c.decodeIfPresent(String.self, forKey: .icon).flatMap { TouchIcons.bitmaps[$0] != nil ? $0 : nil }
        x = try c.decode(Float.self, forKey: .x)
        y = try c.decode(Float.self, forKey: .y)
        w = try c.decode(Float.self, forKey: .w)
        h = try c.decode(Float.self, forKey: .h)
        opacity = try c.decodeIfPresent(Float.self, forKey: .opacity) ?? 0.6
        shape = try c.decodeIfPresent(TouchShape.self, forKey: .shape) ?? .round
        action = try c.decode(TouchAction.self, forKey: .action)
        toggle = try c.decodeIfPresent(Bool.self, forKey: .toggle) ?? false
        passThrough = try c.decodeIfPresent(Bool.self, forKey: .passThrough) ?? false
        show = try c.decodeIfPresent(TouchShow.self, forKey: .show)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(id, forKey: .id)
        try c.encodeIfPresent(label, forKey: .label)
        try c.encodeIfPresent(icon, forKey: .icon)
        try c.encode(x, forKey: .x)
        try c.encode(y, forKey: .y)
        try c.encode(w, forKey: .w)
        try c.encode(h, forKey: .h)
        try c.encode(opacity, forKey: .opacity)
        try c.encode(shape, forKey: .shape)
        try c.encode(action, forKey: .action)
        if toggle { try c.encode(true, forKey: .toggle) }
        if passThrough { try c.encode(true, forKey: .passThrough) }
        try c.encodeIfPresent(show, forKey: .show)
    }

    /// Ohne `show`: Menü, Tastatur, Chat, TRS-Menü (und Esc) immer, sonst nur im Spiel.
    static func defaultShow(_ action: TouchAction) -> TouchShow {
        switch action {
        case .special(.keyboard), .special(.menu), .special(.chat), .special(.trsMenu): return .always
        case let .key(key, _) where key == TouchGlfw.keyEscape: return .always
        default: return .game
        }
    }

    func visible(grabbed: Bool) -> Bool {
        switch show ?? TouchButton.defaultShow(action) {
        case .always: return true
        case .game: return grabbed
        case .menu: return !grabbed
        }
    }

    var isHotbar: Bool { action == .special(.hotbarSwipe) }
    var isJoystick: Bool { if case .joystick = action { return true } else { return false } }

    /// Kurze Beschriftung (≤ 3 Zeichen) als Tasten-Hinweis neben einem Symbol.
    var badge: String? {
        guard icon != nil, let label, label.count <= 3 else { return nil }
        return label
    }
}

struct TouchGestures: Equatable, Codable {
    var tapAttack = true
    var holdUse = true
    var swipeHotbar = true
    var cameraSensitivity: Float = 1
    var haptics = true

    init(tapAttack: Bool = true, holdUse: Bool = true, swipeHotbar: Bool = true, cameraSensitivity: Float = 1, haptics: Bool = true) {
        self.tapAttack = tapAttack
        self.holdUse = holdUse
        self.swipeHotbar = swipeHotbar
        self.cameraSensitivity = cameraSensitivity
        self.haptics = haptics
    }

    private enum K: String, CodingKey { case tapAttack, holdUse, swipeHotbar, cameraSensitivity, haptics }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        tapAttack = try c.decode(Bool.self, forKey: .tapAttack)
        holdUse = try c.decode(Bool.self, forKey: .holdUse)
        swipeHotbar = try c.decode(Bool.self, forKey: .swipeHotbar)
        cameraSensitivity = try c.decode(Float.self, forKey: .cameraSensitivity)
        haptics = try c.decodeIfPresent(Bool.self, forKey: .haptics) ?? true
    }
}

struct TouchLayout: Equatable, Codable {
    var version = TouchLimits.version
    var id: String
    var name: String
    var profile: String
    /// Nur bei unveränderten fertigen Layouts.
    var builtinRev: Int?
    var buttons: [TouchButton]
    var gestures: TouchGestures

    static let profiles: Set<String> = ["pvp", "build", "redstone", "custom"]

    static func isValidId(_ id: String) -> Bool {
        id.range(of: "^[a-z0-9][a-z0-9-]{0,39}$", options: .regularExpression) != nil
    }

    static func parse(_ data: Data) throws -> TouchLayout {
        if data.count > 64 * 1024 { throw TouchLayoutError.invalid("too large") }
        let layout = try JSONDecoder().decode(TouchLayout.self, from: data)
        try validate(layout)
        return layout
    }

    func json() throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try e.encode(self)
    }

    private static func unit(_ v: Float) -> Bool { v.isFinite && v >= -TouchLimits.eps && v <= 1 + TouchLimits.eps }
    private static func size(_ v: Float) -> Bool { v.isFinite && v >= TouchLimits.minSize - TouchLimits.eps && v <= 1 + TouchLimits.eps }

    private static func validChord(_ key: Int?, _ chord: [Int]) -> Bool {
        chord.count <= TouchLimits.maxChord && Set(chord).count == chord.count && chord.allSatisfy { TouchGlfw.isValidKey($0) && $0 != key }
    }

    private static func hasControl(_ s: String) -> Bool { s.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) } }

    /// Gleiche Regeln wie `controls::validate` in Rust.
    static func validate(_ l: TouchLayout) throws {
        if l.version != TouchLimits.version { throw TouchLayoutError.invalid("version") }
        if !isValidId(l.id) { throw TouchLayoutError.invalid("id") }
        let name = l.name.trimmingCharacters(in: .whitespaces)
        if name.isEmpty || name.count > TouchLimits.maxNameChars || hasControl(l.name) { throw TouchLayoutError.invalid("name") }
        if !profiles.contains(l.profile) { throw TouchLayoutError.invalid("profile") }
        if l.buttons.isEmpty || l.buttons.count > TouchLimits.maxButtons { throw TouchLayoutError.invalid("buttons") }
        var ids = Set<String>()
        for b in l.buttons {
            if b.id.range(of: "^[A-Za-z0-9_-]{1,32}$", options: .regularExpression) == nil || !ids.insert(b.id).inserted {
                throw TouchLayoutError.invalid("button id")
            }
            if let label = b.label, label.trimmingCharacters(in: .whitespaces).isEmpty || label.count > TouchLimits.maxLabelChars || hasControl(label) {
                throw TouchLayoutError.invalid("label")
            }
            if b.label == nil && b.icon == nil { throw TouchLayoutError.invalid("label") }
            if !unit(b.x) || !unit(b.y) || !size(b.w) || !size(b.h) || b.x + b.w > 1 + TouchLimits.eps || b.y + b.h > 1 + TouchLimits.eps {
                throw TouchLayoutError.invalid("position")
            }
            if !b.opacity.isFinite || b.opacity < TouchLimits.minOpacity - TouchLimits.eps || b.opacity > 1 + TouchLimits.eps {
                throw TouchLayoutError.invalid("opacity")
            }
            switch b.action {
            case let .key(key, chord): if !TouchGlfw.isValidKey(key) || !validChord(key, chord) { throw TouchLayoutError.invalid("key") }
            case let .mouse(button, chord): if !(0...7).contains(button) || !validChord(nil, chord) { throw TouchLayoutError.invalid("mouse") }
            case let .toggle(key): if !TouchGlfw.isValidKey(key) { throw TouchLayoutError.invalid("key") }
            default: break
            }
        }
        let s = l.gestures.cameraSensitivity
        if !s.isFinite || s < TouchLimits.minSensitivity || s > TouchLimits.maxSensitivity { throw TouchLayoutError.invalid("sensitivity") }
    }

    /// Notfall-TouchLayout, wenn keine Datei lesbar ist.
    static func fallback() -> TouchLayout {
        TouchLayout(id: "pvp", name: "PvP", profile: "pvp", builtinRev: nil, buttons: [
            TouchButton(id: "move", label: "WASD", x: 0.03, y: 0.5, w: 0.19, h: 0.4, opacity: 0.55, action: .joystick(camera: false)),
            TouchButton(id: "attack", icon: "attack", x: 0.8, y: 0.38, w: 0.1, h: 0.2, opacity: 0.7, action: .mouse(TouchGlfw.mouseLeft, chord: []), passThrough: true),
            TouchButton(id: "use", icon: "use", x: 0.69, y: 0.56, w: 0.085, h: 0.17, opacity: 0.65, action: .mouse(TouchGlfw.mouseRight, chord: []), passThrough: true),
            TouchButton(id: "jump", icon: "jump", x: 0.885, y: 0.62, w: 0.09, h: 0.18, opacity: 0.65, action: .key(TouchGlfw.keySpace, chord: [])),
            TouchButton(id: "sneak", icon: "sneak", x: 0.8, y: 0.68, w: 0.07, h: 0.14, action: .key(TouchGlfw.keyLeftShift, chord: [])),
            TouchButton(id: "hotbar", icon: "hotbar", x: 0.3, y: 0.88, w: 0.4, h: 0.12, opacity: 0.15, shape: .rect, action: .special(.hotbarSwipe)),
            TouchButton(id: "inventory", label: "E", icon: "inventory", x: 0.71, y: 0.88, w: 0.05, h: 0.1, shape: .rect, action: .key(69, chord: []), show: .always),
            TouchButton(id: "keyboard", icon: "keyboard", x: 0.075, y: 0.02, w: 0.05, h: 0.1, shape: .rect, action: .special(.keyboard)),
            TouchButton(id: "menu", icon: "menu", x: 0.475, y: 0.01, w: 0.05, h: 0.1, shape: .rect, action: .special(.menu)),
        ], gestures: TouchGestures())
    }
}
