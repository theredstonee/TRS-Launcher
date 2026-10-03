// Umrechnung Anteile ↔ Pixel und Lauf-Stick – gleich wie Geometry.kt / Joystick.kt.
import Foundation

/// Ränder (Notch, Home-Leiste) in Punkten.
struct TouchInsets: Equatable {
    var left: Float = 0
    var top: Float = 0
    var right: Float = 0
    var bottom: Float = 0
}

/// Rechteck in Punkten oder Anteilen.
struct TouchBox: Equatable {
    var x: Float
    var y: Float
    var w: Float
    var h: Float
    var cx: Float { x + w / 2 }
    var cy: Float { y + h / 2 }
    func contains(_ px: Float, _ py: Float) -> Bool { px >= x && px <= x + w && py >= y && py <= y + h }
}

struct TouchCircle: Equatable {
    var cx: Float
    var cy: Float
    var r: Float
}

enum TouchGeometry {
    static let gridStep: Float = 0.01

    /// Sichere Fläche – darauf beziehen sich die Anteile eines Layouts.
    static func safeRect(_ width: Float, _ height: Float, _ insets: TouchInsets = TouchInsets()) -> TouchBox {
        TouchBox(x: insets.left, y: insets.top, w: max(0, width - insets.left - insets.right), h: max(0, height - insets.top - insets.bottom))
    }

    static func toPx(_ b: TouchBox, _ safe: TouchBox) -> TouchBox {
        TouchBox(x: safe.x + b.x * safe.w, y: safe.y + b.y * safe.h, w: b.w * safe.w, h: b.h * safe.h)
    }

    static func toPx(_ b: TouchButton, _ safe: TouchBox) -> TouchBox { toPx(TouchBox(x: b.x, y: b.y, w: b.w, h: b.h), safe) }

    static func fromPx(_ px: TouchBox, _ safe: TouchBox) -> TouchBox {
        if safe.w <= 0 || safe.h <= 0 { return TouchBox(x: 0, y: 0, w: TouchLimits.minSize, h: TouchLimits.minSize) }
        return TouchBox(x: (px.x - safe.x) / safe.w, y: (px.y - safe.y) / safe.h, w: px.w / safe.w, h: px.h / safe.h)
    }

    /// Runde Knöpfe sind Kreise: Durchmesser = kürzere Seite, mittig.
    static func circleOf(_ px: TouchBox) -> TouchCircle { TouchCircle(cx: px.cx, cy: px.cy, r: min(px.w, px.h) / 2) }

    static func hit(_ b: TouchButton, _ safe: TouchBox, _ px: Float, _ py: Float) -> Bool {
        let r = toPx(b, safe)
        if b.shape == .round && !b.isHotbar {
            let c = circleOf(r)
            let dx = px - c.cx
            let dy = py - c.cy
            let rr = c.r * 1.1
            return dx * dx + dy * dy <= rr * rr
        }
        return r.contains(px, py)
    }

    static func snap(_ v: Float, _ step: Float = gridStep) -> Float { (v / step).rounded() * step }

    private static func round4(_ v: Float) -> Float { (v * 10000).rounded() / 10000 }

    /// Hält ein Rechteck (Anteile) vollständig in der sicheren Fläche.
    static func clamp(_ r: TouchBox) -> TouchBox {
        let w = min(1, max(TouchLimits.minSize, r.w))
        let h = min(1, max(TouchLimits.minSize, r.h))
        let x = min(1 - w, max(0, r.x))
        let y = min(1 - h, max(0, r.y))
        return TouchBox(x: round4(x), y: round4(y), w: round4(w), h: round4(h))
    }

    /// `l,t,r,b` für `-Dtrs.safeInsets` (Pixel).
    static func formatInsets(_ i: TouchInsets, scale: Float = 1) -> String {
        [i.left, i.top, i.right, i.bottom].map { String(Int(($0 * scale).rounded())) }.joined(separator: ",")
    }
}

/// Lauf-Stick: Fingerlage relativ zur Mitte → gedrückte Tasten (8 Richtungen,
/// ganz nach vorn über den Rand hinaus = Sprinten).
enum TouchJoystick {
    static let deadzone: Float = 0.22
    static let axis: Float = 0.38
    static let sprintRadius: Float = 1.05
    static let sprintAngle: Double = 30

    struct Vec: Equatable {
        var x: Float
        var y: Float
        var raw: Float
    }

    static func vector(cx: Float, cy: Float, radius: Float, px: Float, py: Float) -> Vec {
        if radius <= 0 { return Vec(x: 0, y: 0, raw: 0) }
        let dx = (px - cx) / radius
        let dy = (py - cy) / radius
        let len = (dx * dx + dy * dy).squareRoot()
        return len > 1 ? Vec(x: dx / len, y: dy / len, raw: len) : Vec(x: dx, y: dy, raw: len)
    }

    static func keys(_ v: Vec) -> Set<Int> {
        if v.raw < deadzone { return [] }
        var out = Set<Int>()
        if v.y < -axis { out.insert(TouchGlfw.keyW) }
        if v.y > axis { out.insert(TouchGlfw.keyS) }
        if v.x < -axis { out.insert(TouchGlfw.keyA) }
        if v.x > axis { out.insert(TouchGlfw.keyD) }
        if v.raw >= sprintRadius {
            let angle = atan2(Double(v.x), Double(-v.y)) * 180 / .pi
            if abs(angle) <= sprintAngle { out.insert(TouchGlfw.keyLeftControl) }
        }
        return out
    }
}
