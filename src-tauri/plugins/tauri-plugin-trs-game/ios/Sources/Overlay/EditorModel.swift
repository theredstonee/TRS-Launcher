// Editor im Spiel ohne UIKit – Spiegel von EditorModel.kt, LayoutStore.kt und ActionPresets.kt.
import Foundation

final class TouchEditorModel {
    private(set) var layout: TouchLayout
    private let original: TouchLayout
    var selectedId: String?
    var grid = true
    private var dragging: (id: String, resize: Bool, start: TouchLayout, x: Float, y: Float)?

    init(_ start: TouchLayout) {
        layout = start
        original = start
    }

    var selected: TouchButton? { layout.buttons.first { $0.id == selectedId } }
    var dirty: Bool { layout != original }

    func handleBox(_ b: TouchButton, _ safe: TouchBox, _ handle: Float) -> TouchBox {
        let r = TouchGeometry.toPx(b, safe)
        return TouchBox(x: r.x + r.w - handle / 2, y: r.y + r.h - handle / 2, w: handle, h: handle)
    }

    func begin(_ px: Float, _ py: Float, _ safe: TouchBox, _ handle: Float) {
        if let sel = selected, handleBox(sel, safe, handle).contains(px, py) {
            dragging = (sel.id, true, layout, px, py)
            return
        }
        let hit = layout.buttons.last { TouchGeometry.hit($0, safe, px, py) || TouchGeometry.toPx($0, safe).contains(px, py) }
        selectedId = hit?.id
        dragging = hit.map { ($0.id, false, layout, px, py) }
    }

    func drag(to px: Float, _ py: Float, _ safe: TouchBox) {
        guard let d = dragging, safe.w > 0, safe.h > 0 else { return }
        let dx = (px - d.x) / safe.w
        let dy = (py - d.y) / safe.h
        layout = d.resize ? TouchEditorModel.resize(d.start, d.id, dx, dy, grid) : TouchEditorModel.move(d.start, d.id, dx, dy, grid)
    }

    func end() { dragging = nil }

    @discardableResult
    func add(_ action: TouchAction, label: String?, icon: String?) -> String? {
        if layout.buttons.count >= TouchLimits.maxButtons { return nil }
        let taken = Set(layout.buttons.map { $0.id })
        var n = layout.buttons.count + 1
        while taken.contains("b\(n)") { n += 1 }
        var x: Float = 0.47
        var y: Float = 0.42
        while layout.buttons.contains(where: { abs($0.x - x) < 0.005 && abs($0.y - y) < 0.005 }) && y < 0.8 {
            x += 0.02
            y += 0.04
        }
        let big = { if case .joystick = action { return true } else { return false } }()
        let w: Float = big ? 0.19 : 0.06
        let h: Float = big ? 0.4 : 0.12
        let r = TouchGeometry.clamp(TouchBox(x: x, y: y, w: w, h: h))
        let b = TouchButton(id: "b\(n)", label: label.map { String($0.prefix(TouchLimits.maxLabelChars)) } ?? (icon == nil ? "?" : nil), icon: icon,
                            x: r.x, y: r.y, w: r.w, h: r.h, shape: action == .special(.hotbarSwipe) ? .rect : .round, action: action)
        layout.buttons.append(b)
        selectedId = b.id
        return b.id
    }

    func remove() {
        guard let id = selectedId, layout.buttons.count > 1 else { return }
        layout.buttons.removeAll { $0.id == id }
        selectedId = nil
    }

    func update(_ change: (inout TouchButton) -> Void) {
        guard let id = selectedId, let i = layout.buttons.firstIndex(where: { $0.id == id }) else { return }
        change(&layout.buttons[i])
    }

    func setOpacity(_ v: Float) { update { $0.opacity = min(1, max(TouchLimits.minOpacity, v)) } }

    func cycleShape() { update { $0.shape = $0.shape == .round ? .rect : .round } }

    func setAction(_ action: TouchAction, label: String?, icon: String?) {
        update { b in
            b.action = action
            b.label = label.map { String($0.prefix(TouchLimits.maxLabelChars)) } ?? (icon == nil ? (b.label ?? "?") : nil)
            b.icon = icon
            b.toggle = false
        }
    }

    func scale(_ factor: Float) {
        update { b in
            let w = b.w * factor
            let h = b.h * factor
            let r = TouchGeometry.clamp(TouchBox(x: b.x + (b.w - w) / 2, y: b.y + (b.h - h) / 2, w: w, h: h))
            b.x = r.x
            b.y = r.y
            b.w = r.w
            b.h = r.h
        }
    }

    func result() throws -> TouchLayout {
        var out = layout
        out.builtinRev = nil
        out.name = out.name.trimmingCharacters(in: .whitespaces)
        try TouchLayout.validate(out)
        return out
    }

    static func move(_ l: TouchLayout, _ id: String, _ dx: Float, _ dy: Float, _ grid: Bool) -> TouchLayout {
        var out = l
        for i in out.buttons.indices where out.buttons[i].id == id {
            var x = out.buttons[i].x + dx
            var y = out.buttons[i].y + dy
            if grid {
                x = TouchGeometry.snap(x)
                y = TouchGeometry.snap(y)
            }
            let r = TouchGeometry.clamp(TouchBox(x: x, y: y, w: out.buttons[i].w, h: out.buttons[i].h))
            out.buttons[i].x = r.x
            out.buttons[i].y = r.y
        }
        return out
    }

    static func resize(_ l: TouchLayout, _ id: String, _ dw: Float, _ dh: Float, _ grid: Bool) -> TouchLayout {
        var out = l
        for i in out.buttons.indices where out.buttons[i].id == id {
            let b = out.buttons[i]
            var w = b.w + dw
            var h = b.h + dh
            if grid {
                w = TouchGeometry.snap(w)
                h = TouchGeometry.snap(h)
            }
            w = min(w, 1 - b.x)
            h = min(h, 1 - b.y)
            let r = TouchGeometry.clamp(TouchBox(x: b.x, y: b.y, w: w, h: h))
            out.buttons[i].x = r.x
            out.buttons[i].y = r.y
            out.buttons[i].w = r.w
            out.buttons[i].h = r.h
        }
        return out
    }
}

/// Layout-Dateien in `<App-Daten>/controls/<id>.json` (siehe LayoutStore.kt).
final class TouchLayoutStore {
    static let defaultId = "pvp"
    private let dir: URL

    init(dir: URL) { self.dir = dir }

    func file(_ id: String) -> URL? { TouchLayout.isValidId(id) ? dir.appendingPathComponent("\(id).json") : nil }

    func load(_ id: String?) -> TouchLayout {
        for candidate in [id, TouchLayoutStore.defaultId].compactMap({ $0 }) {
            if let l = read(candidate) { return l }
        }
        return TouchLayout.fallback()
    }

    func read(_ id: String) -> TouchLayout? {
        guard let url = file(id), let data = try? Data(contentsOf: url), let l = try? TouchLayout.parse(data), l.id == id else { return nil }
        return l
    }

    func save(_ layout: TouchLayout) throws {
        try TouchLayout.validate(layout)
        guard let url = file(layout.id) else { throw TouchLayoutError.invalid("id") }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try layout.json().write(to: url, options: .atomic)
    }
}

/// Overlay ausblenden, sobald Controller oder Maus benutzt werden; Berührung holt es zurück.
final class TouchVisibilityPolicy {
    private(set) var hidden = false

    func onHardwareInput() -> Bool {
        if hidden { return false }
        hidden = true
        return true
    }

    func onTouch() -> Bool {
        if !hidden { return false }
        hidden = false
        return true
    }
}

struct TouchActionPreset {
    let key: String
    let action: TouchAction
    let icon: String?
    let label: String?
}

enum TouchActionPresets {
    static let all: [TouchActionPreset] = [
        TouchActionPreset(key: "moveStick", action: .joystick(camera: false), icon: nil, label: "WASD"),
        TouchActionPreset(key: "cameraStick", action: .joystick(camera: true), icon: nil, label: "Cam"),
        TouchActionPreset(key: "jump", action: .key(TouchGlfw.keySpace, chord: []), icon: "jump", label: nil),
        TouchActionPreset(key: "sneak", action: .key(TouchGlfw.keyLeftShift, chord: []), icon: "sneak", label: nil),
        TouchActionPreset(key: "sneakToggle", action: .toggle(TouchGlfw.keyLeftShift), icon: "sneak", label: nil),
        TouchActionPreset(key: "sprint", action: .toggle(TouchGlfw.keyLeftControl), icon: "sprint", label: nil),
        TouchActionPreset(key: "attack", action: .mouse(TouchGlfw.mouseLeft, chord: []), icon: "attack", label: nil),
        TouchActionPreset(key: "use", action: .mouse(TouchGlfw.mouseRight, chord: []), icon: "use", label: nil),
        TouchActionPreset(key: "place", action: .mouse(TouchGlfw.mouseRight, chord: []), icon: "place", label: nil),
        TouchActionPreset(key: "breakBlock", action: .mouse(TouchGlfw.mouseLeft, chord: []), icon: "break", label: nil),
        TouchActionPreset(key: "pick", action: .mouse(TouchGlfw.mouseMiddle, chord: []), icon: "pick", label: nil),
        TouchActionPreset(key: "sneakPlace", action: .mouse(TouchGlfw.mouseRight, chord: [TouchGlfw.keyLeftShift]), icon: "place", label: "Shift"),
        TouchActionPreset(key: "inventory", action: .key(69, chord: []), icon: "inventory", label: "E"),
        TouchActionPreset(key: "drop", action: .key(81, chord: []), icon: "drop", label: "Q"),
        TouchActionPreset(key: "offhand", action: .key(70, chord: []), icon: "swap", label: "F"),
        TouchActionPreset(key: "perspective", action: .key(294, chord: []), icon: "perspective", label: "F5"),
        TouchActionPreset(key: "zoom", action: .key(86, chord: []), icon: "zoom", label: nil),
        TouchActionPreset(key: "debug", action: .key(292, chord: []), icon: "debug", label: "F3"),
        TouchActionPreset(key: "chunks", action: .key(71, chord: [292]), icon: nil, label: "F3+G"),
        TouchActionPreset(key: "hitboxes", action: .key(66, chord: [292]), icon: nil, label: "F3+B"),
        TouchActionPreset(key: "signal", action: .key(295, chord: []), icon: "redstone", label: "F6"),
        TouchActionPreset(key: "signalLegacy", action: .key(297, chord: []), icon: "redstone", label: "F8"),
        TouchActionPreset(key: "chat", action: .special(.chat), icon: "chat", label: nil),
        TouchActionPreset(key: "keyboard", action: .special(.keyboard), icon: "keyboard", label: nil),
        TouchActionPreset(key: "menu", action: .special(.menu), icon: "menu", label: nil),
        TouchActionPreset(key: "trsMenu", action: .special(.trsMenu), icon: "trs", label: nil),
        TouchActionPreset(key: "emotes", action: .special(.emoteWheel), icon: "emote", label: nil),
        TouchActionPreset(key: "hotbar", action: .special(.hotbarSwipe), icon: "hotbar", label: nil),
        TouchActionPreset(key: "prev", action: .special(.scrollUp), icon: "prev", label: nil),
        TouchActionPreset(key: "next", action: .special(.scrollDown), icon: "next", label: nil),
    ]

    static let keys: [Int] = [32, 340, 341, 342, 344, 256, 257, 258, 259, 265, 264, 263, 262] + Array(65...90) + Array(48...57) + Array(290...301)

    static func keyName(_ code: Int) -> String {
        switch code {
        case 32: return "Space"
        case 256: return "Esc"
        case 257: return "Enter"
        case 258: return "Tab"
        case 259: return "⌫"
        case 262: return "→"
        case 263: return "←"
        case 264: return "↓"
        case 265: return "↑"
        case 340: return "L-Shift"
        case 341: return "L-Ctrl"
        case 342: return "L-Alt"
        case 344: return "R-Shift"
        case 345: return "R-Ctrl"
        case 346: return "R-Alt"
        case 48...57, 65...90: return String(UnicodeScalar(UInt8(code)))
        case 290...314: return "F\(code - 289)"
        default: return "#\(code)"
        }
    }
}
