// Herz des Overlays ohne UIKit – Spiegel von OverlayController.kt/CameraGesture.kt
// (die Kotlin-Seite hat die Tests; Änderungen immer in beiden machen).
import Foundation

/// Gedrückte Tasten/Maustasten mit Zähler – zwei Quellen für dieselbe Taste stören sich nicht.
final class TouchHeldInputs {
    private unowned let sink: TouchInputSink
    private var keys: [Int: Int] = [:]
    private var mouse: [Int: Int] = [:]

    init(_ sink: TouchInputSink) { self.sink = sink }

    func isDown(_ key: Int) -> Bool { (keys[key] ?? 0) > 0 }

    func mods() -> Int {
        var m = 0
        if isDown(TouchGlfw.keyLeftShift) || isDown(TouchGlfw.keyRightShift) { m |= TouchGlfw.modShift }
        if isDown(TouchGlfw.keyLeftControl) || isDown(TouchGlfw.keyRightControl) { m |= TouchGlfw.modControl }
        if isDown(TouchGlfw.keyLeftAlt) || isDown(TouchGlfw.keyRightAlt) { m |= TouchGlfw.modAlt }
        return m
    }

    func keyDown(_ key: Int) {
        let n = (keys[key] ?? 0) + 1
        keys[key] = n
        if n == 1 { sink.sendKey(key, scancode: 0, down: true, mods: mods()) }
    }

    func keyUp(_ key: Int) {
        let n = (keys[key] ?? 0) - 1
        if n < 0 { return }
        if n == 0 {
            keys[key] = nil
            sink.sendKey(key, scancode: 0, down: false, mods: mods())
        } else {
            keys[key] = n
        }
    }

    func mouseDown(_ button: Int) {
        let n = (mouse[button] ?? 0) + 1
        mouse[button] = n
        if n == 1 { sink.sendMouseButton(button, down: true) }
    }

    func mouseUp(_ button: Int) {
        let n = (mouse[button] ?? 0) - 1
        if n < 0 { return }
        if n == 0 {
            mouse[button] = nil
            sink.sendMouseButton(button, down: false)
        } else {
            mouse[button] = n
        }
    }

    func tapKey(_ key: Int) {
        keyDown(key)
        keyUp(key)
    }

    func click(_ button: Int) {
        mouseDown(button)
        mouseUp(button)
    }

    func releaseAll() {
        for k in Array(keys.keys) {
            keys[k] = nil
            sink.sendKey(k, scancode: 0, down: false, mods: mods())
        }
        for b in Array(mouse.keys) {
            mouse[b] = nil
            sink.sendMouseButton(b, down: false)
        }
    }
}

/// Zeiten (Millisekunden) und Wege (Punkte) der Gesten.
enum GestureTiming {
    static let holdMs: Int64 = 300
    static let longPressMs: Int64 = 450
    static let slopPt: Float = 8
    /// Maus-Einheiten je Punkt Wischen bei Empfindlichkeit 1.
    static let cameraPerPt: Float = 3
    static let scrollStepPt: Float = 28
}

/// Ein Finger auf der freien Fläche (siehe CameraGesture.kt).
final class TouchCameraGesture {
    enum State { case pending, moved, holding, dragging, done }

    private let held: TouchHeldInputs
    private unowned let sink: TouchInputSink
    let grabbed: Bool
    private let gestures: TouchGestures
    private let start: Int64
    private let startX: Float
    private let startY: Float
    private var lastX: Float
    private var lastY: Float
    private(set) var state = State.pending

    init(held: TouchHeldInputs, sink: TouchInputSink, grabbed: Bool, gestures: TouchGestures, x: Float, y: Float, start: Int64) {
        self.held = held
        self.sink = sink
        self.grabbed = grabbed
        self.gestures = gestures
        self.start = start
        startX = x
        startY = y
        lastX = x
        lastY = y
        if !grabbed { sink.moveMouseAbsolute(x: x, y: y) }
    }

    private var holdButton: Int { gestures.holdUse ? TouchGlfw.mouseRight : TouchGlfw.mouseLeft }
    private var tapButton: Int { gestures.tapAttack ? TouchGlfw.mouseLeft : TouchGlfw.mouseRight }

    private func beyondSlop(_ x: Float, _ y: Float) -> Bool { abs(x - startX) > GestureTiming.slopPt || abs(y - startY) > GestureTiming.slopPt }

    func move(_ x: Float, _ y: Float) {
        let dx = x - lastX
        let dy = y - lastY
        lastX = x
        lastY = y
        if grabbed {
            if dx != 0 || dy != 0 {
                let k = GestureTiming.cameraPerPt * gestures.cameraSensitivity
                sink.moveMouseRelative(dx: dx * k, dy: dy * k)
            }
            if state == .pending && beyondSlop(x, y) { state = .moved }
        } else {
            sink.moveMouseAbsolute(x: x, y: y)
            if state == .pending && beyondSlop(x, y) {
                state = .dragging
                held.mouseDown(TouchGlfw.mouseLeft)
            }
        }
    }

    @discardableResult
    func tick(_ now: Int64) -> Bool {
        guard state == .pending else { return false }
        let elapsed = now - start
        if grabbed && elapsed >= GestureTiming.holdMs {
            state = .holding
            held.mouseDown(holdButton)
            return false
        }
        if !grabbed && elapsed >= GestureTiming.longPressMs {
            state = .done
            held.click(TouchGlfw.mouseRight)
            return false
        }
        return true
    }

    func up(_ now: Int64) {
        tick(now)
        switch state {
        case .pending: held.click(grabbed ? tapButton : TouchGlfw.mouseLeft)
        case .holding: held.mouseUp(holdButton)
        case .dragging: held.mouseUp(TouchGlfw.mouseLeft)
        case .moved, .done: break
        }
        state = .done
    }

    func cancel() {
        switch state {
        case .holding: held.mouseUp(holdButton)
        case .dragging: held.mouseUp(TouchGlfw.mouseLeft)
        default: break
        }
        state = .done
    }
}

/// Ordnet Finger Knöpfen, Sticks oder der freien Fläche zu (siehe OverlayController.kt).
final class TouchOverlayController {
    private unowned let sink: TouchInputSink
    private(set) var layout: TouchLayout
    private(set) var safe = TouchBox(x: 0, y: 0, w: 0, h: 0)
    let held: TouchHeldInputs
    private(set) var latched = Set<String>()
    private(set) var pressed = Set<String>()
    private(set) var knobs: [String: TouchJoystick.Vec] = [:]
    private(set) var keyboardShown = false
    var onHaptic: (() -> Void)?

    private enum Target {
        case button(TouchButton, lastX: Float, lastY: Float)
        case stick(TouchButton, keys: Set<Int>)
        case hotbar(TouchButton, slot: Int)
        case camera(TouchCameraGesture)
        case scroll(lastY: Float, rest: Float)
        case ignored
    }

    private var pointers: [Int: Target] = [:]
    private var lastTick: Int64 = 0

    static let cameraStickPt: Float = 220

    init(sink: TouchInputSink, layout: TouchLayout) {
        self.sink = sink
        self.layout = layout
        held = TouchHeldInputs(sink)
    }

    func setSize(_ w: Float, _ h: Float, _ insets: TouchInsets) {
        safe = TouchGeometry.safeRect(w, h, insets)
    }

    func setLayout(_ layout: TouchLayout) {
        cancelAll()
        latched.removeAll()
        self.layout = layout
    }

    func visibleButtons() -> [TouchButton] {
        let grabbed = sink.isGrabbed()
        return layout.buttons.filter { $0.visible(grabbed: grabbed) }
    }

    func down(_ pointer: Int, _ x: Float, _ y: Float, _ now: Int64) {
        if pointers[pointer] != nil { up(pointer, x, y, now) }
        let grabbed = sink.isGrabbed()
        let button = layout.buttons.filter { $0.visible(grabbed: grabbed) }.last { TouchGeometry.hit($0, safe, x, y) }
        var target: Target
        if let b = button {
            if b.isJoystick {
                target = .stick(b, keys: [])
                target = stickMove(target, x, y)
            } else if b.isHotbar {
                let slot = slotAt(b, x)
                held.tapKey(TouchGlfw.key1 + slot)
                target = .hotbar(b, slot: slot)
            } else {
                press(b)
                target = .button(b, lastX: x, lastY: y)
            }
            pressed.insert(b.id)
            if layout.gestures.haptics { onHaptic?() }
        } else {
            target = freeArea(x, y, now, grabbed)
        }
        pointers[pointer] = target
    }

    private func freeArea(_ x: Float, _ y: Float, _ now: Int64, _ grabbed: Bool) -> Target {
        let cameras: [TouchCameraGesture] = pointers.values.compactMap { if case let .camera(g) = $0 { return g } else { return nil } }
        if !grabbed && !cameras.isEmpty {
            cameras.forEach { $0.cancel() }
            return .scroll(lastY: y, rest: 0)
        }
        if grabbed && cameras.contains(where: { $0.grabbed }) { return .ignored }
        return .camera(TouchCameraGesture(held: held, sink: sink, grabbed: grabbed, gestures: layout.gestures, x: x, y: y, start: now))
    }

    func move(_ pointer: Int, _ x: Float, _ y: Float) {
        guard let t = pointers[pointer] else { return }
        switch t {
        case let .button(b, lastX, lastY):
            if b.passThrough && sink.isGrabbed() {
                let k = GestureTiming.cameraPerPt * layout.gestures.cameraSensitivity
                if x != lastX || y != lastY { sink.moveMouseRelative(dx: (x - lastX) * k, dy: (y - lastY) * k) }
            }
            pointers[pointer] = .button(b, lastX: x, lastY: y)
        case .stick:
            pointers[pointer] = stickMove(t, x, y)
        case let .hotbar(b, slot):
            guard layout.gestures.swipeHotbar else { return }
            let next = slotAt(b, x)
            if next != slot {
                held.tapKey(TouchGlfw.key1 + next)
                pointers[pointer] = .hotbar(b, slot: next)
            }
        case let .camera(g):
            g.move(x, y)
        case let .scroll(lastY, rest):
            var r = rest + (y - lastY)
            while abs(r) >= GestureTiming.scrollStepPt {
                let dir: Float = r > 0 ? 1 : -1
                sink.scroll(dx: 0, dy: dir)
                r -= dir * GestureTiming.scrollStepPt
            }
            pointers[pointer] = .scroll(lastY: y, rest: r)
        case .ignored:
            break
        }
    }

    func up(_ pointer: Int, _ x: Float, _ y: Float, _ now: Int64) {
        guard let t = pointers.removeValue(forKey: pointer) else { return }
        switch t {
        case let .button(b, _, _):
            release(b)
            pressed.remove(b.id)
        case let .stick(b, keys):
            keys.forEach { held.keyUp($0) }
            knobs[b.id] = nil
            pressed.remove(b.id)
        case let .hotbar(b, _):
            pressed.remove(b.id)
        case let .camera(g):
            g.move(x, y)
            g.up(now)
        case .scroll, .ignored:
            break
        }
    }

    func cancelAll() {
        for t in pointers.values {
            switch t {
            case let .button(b, _, _): release(b)
            case let .stick(_, keys): keys.forEach { held.keyUp($0) }
            case let .camera(g): g.cancel()
            default: break
            }
        }
        pointers.removeAll()
        pressed.removeAll()
        knobs.removeAll()
    }

    /// Pro Bild aufrufen, solange `true` zurückkommt (Halten, Kamera-Stick).
    @discardableResult
    func tick(_ now: Int64) -> Bool {
        let dt: Float = lastTick == 0 ? 0 : Float(min(max(now - lastTick, 0), 100)) / 1000
        lastTick = now
        var more = false
        for t in pointers.values {
            switch t {
            case let .camera(g):
                if g.tick(now) { more = true }
            case let .stick(b, _):
                if case .joystick(camera: true) = b.action {
                    if let v = knobs[b.id], v.raw >= TouchJoystick.deadzone {
                        let k = GestureTiming.cameraPerPt * layout.gestures.cameraSensitivity * TouchOverlayController.cameraStickPt * dt
                        sink.moveMouseRelative(dx: v.x * k, dy: v.y * k)
                    }
                    more = true
                }
            default:
                break
            }
        }
        if !more { lastTick = 0 }
        return more
    }

    // --- Knöpfe ---------------------------------------------------------------------

    private func isLatching(_ b: TouchButton) -> Bool {
        switch b.action {
        case .toggle: return true
        case .key, .mouse: return b.toggle
        default: return false
        }
    }

    private func pressAction(_ b: TouchButton) {
        switch b.action {
        case let .key(key, chord):
            chord.forEach { held.keyDown($0) }
            held.keyDown(key)
        case let .mouse(button, chord):
            chord.forEach { held.keyDown($0) }
            held.mouseDown(button)
        case let .toggle(key):
            held.keyDown(key)
        default:
            break
        }
    }

    private func releaseAction(_ b: TouchButton) {
        switch b.action {
        case let .key(key, chord):
            held.keyUp(key)
            chord.reversed().forEach { held.keyUp($0) }
        case let .mouse(button, chord):
            held.mouseUp(button)
            chord.reversed().forEach { held.keyUp($0) }
        case let .toggle(key):
            held.keyUp(key)
        default:
            break
        }
    }

    private func press(_ b: TouchButton) {
        if isLatching(b) {
            if latched.remove(b.id) != nil {
                releaseAction(b)
            } else {
                latched.insert(b.id)
                pressAction(b)
            }
            return
        }
        switch b.action {
        case .key, .mouse: pressAction(b)
        case let .special(s):
            switch s {
            case .keyboard: setKeyboard(!keyboardShown)
            case .menu: held.keyDown(TouchGlfw.keyEscape)
            case .trsMenu: held.keyDown(TouchGlfw.keyF13)
            case .emoteWheel: held.keyDown(TouchGlfw.keyF14)
            case .chat:
                held.tapKey(TouchGlfw.keyT)
                setKeyboard(true)
            case .scrollUp: sink.scroll(dx: 0, dy: 1)
            case .scrollDown: sink.scroll(dx: 0, dy: -1)
            case .hotbarSwipe: break
            }
        default:
            break
        }
    }

    private func release(_ b: TouchButton) {
        if isLatching(b) { return }
        switch b.action {
        case .key, .mouse: releaseAction(b)
        case .special(.menu): held.keyUp(TouchGlfw.keyEscape)
        case .special(.trsMenu): held.keyUp(TouchGlfw.keyF13)
        case .special(.emoteWheel): held.keyUp(TouchGlfw.keyF14)
        default: break
        }
    }

    func setKeyboard(_ show: Bool) {
        keyboardShown = show
        sink.showKeyboard(show)
    }

    private func stickMove(_ t: Target, _ x: Float, _ y: Float) -> Target {
        guard case let .stick(b, keys) = t else { return t }
        let c = TouchGeometry.circleOf(TouchGeometry.toPx(b, safe))
        let v = TouchJoystick.vector(cx: c.cx, cy: c.cy, radius: c.r, px: x, py: y)
        knobs[b.id] = v
        if case .joystick(camera: true) = b.action { return t }
        let next = TouchJoystick.keys(v)
        keys.subtracting(next).forEach { held.keyUp($0) }
        next.subtracting(keys).forEach { held.keyDown($0) }
        return .stick(b, keys: next)
    }

    func slotAt(_ b: TouchButton, _ x: Float) -> Int {
        let r = TouchGeometry.toPx(b, safe)
        if r.w <= 0 { return 0 }
        return min(8, max(0, Int(((x - r.x) / r.w * 9).rounded(.down))))
    }
}
