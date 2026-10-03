// Overlay über der Spielfläche (UIKit) – Spiegel von TouchOverlayView.kt/TouchOverlay.kt.
// Die Logik steckt in TouchOverlayController/TouchEditorModel; hier nur Zeichnen,
// Berührungen, Werkzeugleiste des Editors und die Verbindung zur Engine.
#if canImport(UIKit)
import GameController
import UIKit

/// Was die Engine beim Start übergibt.
struct TouchOverlayConfig {
    /// `<App-Daten>/controls` – dort liegen die Layouts.
    let controlsDir: URL
    /// Layout-ID aus `GameLaunchSpec.touchProfile` (`nil` = PvP).
    let profileId: String?
    /// Feste Ränder in Punkten; `nil` = safeAreaInsets.
    var insets: TouchInsets?
}

protocol TouchOverlayHandle: AnyObject {
    var view: UIView { get }
    /// Editor an/aus (Pause → „Steuerung bearbeiten“).
    func setEditing(_ editing: Bool)
    func reload(_ profileId: String?)
    /// Engine meldet Controller-/Maus-/Tastatur-Eingabe → Overlay ausblenden.
    func onHardwareInput()
    /// `l,t,r,b` in Pixeln für `-Dtrs.safeInsets`.
    func safeInsets() -> String
    func detach()
}

protocol OverlayProvider {
    func attach(to parent: UIView, input: GameInput, config: TouchOverlayConfig) -> TouchOverlayHandle
}

/// Verbindet die Engine-Schnittstelle mit der testbaren Overlay-Logik.
final class GameInputSink: TouchInputSink {
    private let input: GameInput
    init(_ input: GameInput) { self.input = input }
    func sendKey(_ glfwKey: Int, scancode: Int, down: Bool, mods: Int) { input.sendKey(glfwKey: glfwKey, scancode: scancode, down: down, mods: mods) }
    func sendChar(_ codepoint: Int) { input.sendChar(codepoint: codepoint) }
    func sendMouseButton(_ button: Int, down: Bool) { input.sendMouseButton(button: button, down: down) }
    func moveMouseRelative(dx: Float, dy: Float) { input.moveMouseRelative(dx: dx, dy: dy) }
    func moveMouseAbsolute(x: Float, y: Float) { input.moveMouseAbsolute(x: x, y: y) }
    func scroll(dx: Float, dy: Float) { input.scroll(dx: dx, dy: dy) }
    func isGrabbed() -> Bool { input.isGrabbed() }
    func showKeyboard(_ show: Bool) { input.showKeyboard(show: show) }
}

final class TouchOverlay: OverlayProvider {
    static let shared = TouchOverlay()

    func attach(to parent: UIView, input: GameInput, config: TouchOverlayConfig) -> TouchOverlayHandle {
        let sink = GameInputSink(input)
        let store = TouchLayoutStore(dir: config.controlsDir)
        let view = TouchOverlayView(sink: sink, layout: store.load(config.profileId), store: store, fixedInsets: config.insets)
        view.frame = parent.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        parent.addSubview(view)
        return view
    }
}

final class TouchOverlayView: UIView, TouchOverlayHandle {
    private let sink: TouchInputSink
    private let store: TouchLayoutStore
    private let fixedInsets: TouchInsets?
    let controller: TouchOverlayController
    private let policy = TouchVisibilityPolicy()
    private var editor: TouchEditorModel?
    private var ids: [ObjectIdentifier: Int] = [:]
    private var nextId = 0
    private var link: CADisplayLink?
    private var lastGrabbed: Bool
    private let haptic = UIImpactFeedbackGenerator(style: .light)
    private let toolbar = UIStackView()
    private let toolbarScroll = UIScrollView()
    private var observers: [NSObjectProtocol] = []
    private var selectionButtons: [UIButton] = []

    var view: UIView { self }

    private static let fill = UIColor(red: 0x17 / 255, green: 0x17 / 255, blue: 0x1E / 255, alpha: 1)
    private static let fillDown = UIColor(red: 0x33 / 255, green: 0x16 / 255, blue: 0x14 / 255, alpha: 1)
    private static let border = UIColor(red: 0x33 / 255, green: 0x33 / 255, blue: 0x43 / 255, alpha: 1)
    private static let text = UIColor(red: 0xF3 / 255, green: 0xF3 / 255, blue: 0xF8 / 255, alpha: 1)
    private static let accent = UIColor(red: 0xE0 / 255, green: 0x28 / 255, blue: 0x1E / 255, alpha: 1)
    private static let lamp = UIColor(red: 1, green: 0xB8 / 255, blue: 0x4D / 255, alpha: 1)
    private static let knob = UIColor(red: 0x25 / 255, green: 0x25 / 255, blue: 0x31 / 255, alpha: 1)
    private static let handle: Float = 22

    init(sink: TouchInputSink, layout: TouchLayout, store: TouchLayoutStore, fixedInsets: TouchInsets?) {
        self.sink = sink
        self.store = store
        self.fixedInsets = fixedInsets
        controller = TouchOverlayController(sink: sink, layout: layout)
        lastGrabbed = sink.isGrabbed()
        super.init(frame: .zero)
        isMultipleTouchEnabled = true
        isOpaque = false
        backgroundColor = .clear
        controller.onHaptic = { [weak self] in self?.haptic.impactOccurred() }
        buildToolbar()
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { [weak self] _ in self?.onHardwareInput() })
        if #available(iOS 14.0, *) {
            observers.append(center.addObserver(forName: .GCMouseDidConnect, object: nil, queue: .main) { [weak self] _ in self?.onHardwareInput() })
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }

    // --- Größe und Ränder ----------------------------------------------------------

    private var currentInsets: TouchInsets {
        if let fixedInsets { return fixedInsets }
        let i = safeAreaInsets
        return TouchInsets(left: Float(i.left), top: Float(i.top), right: Float(i.right), bottom: Float(i.bottom))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        controller.setSize(Float(bounds.width), Float(bounds.height), currentInsets)
        toolbarScroll.frame = CGRect(x: 0, y: safeAreaInsets.top, width: bounds.width, height: 52)
        toolbar.frame = CGRect(x: 0, y: 0, width: max(bounds.width, toolbar.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize).width), height: 52)
        toolbarScroll.contentSize = toolbar.frame.size
        setNeedsDisplay()
    }

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        setNeedsLayout()
    }

    func safeInsets() -> String { TouchGeometry.formatInsets(currentInsets, scale: Float(contentScaleFactor)) }

    // --- Griff für die Engine ---------------------------------------------------------

    func setEditing(_ editing: Bool) {
        controller.cancelAll()
        editor = editing ? TouchEditorModel(controller.layout) : nil
        toolbarScroll.isHidden = !editing
        refreshToolbar()
        setNeedsDisplay()
    }

    func reload(_ profileId: String?) {
        controller.setLayout(store.load(profileId))
        setNeedsDisplay()
    }

    func onHardwareInput() {
        if policy.onHardwareInput() {
            controller.cancelAll()
            setNeedsDisplay()
        }
    }

    func detach() {
        link?.invalidate()
        link = nil
        controller.cancelAll()
        controller.held.releaseAll()
        removeFromSuperview()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        link?.invalidate()
        guard window != nil else { return }
        let l = CADisplayLink(target: self, selector: #selector(onFrame(_:)))
        l.add(to: .main, forMode: .common)
        link = l
    }

    /// Ticks für Halten/Kamera-Stick und „Menü auf/zu“ (sichtbare Knöpfe).
    @objc private func onFrame(_ l: CADisplayLink) {
        if controller.tick(Self.now()) { setNeedsDisplay() }
        let g = sink.isGrabbed()
        if g != lastGrabbed {
            lastGrabbed = g
            setNeedsDisplay()
        }
    }

    private static func now() -> Int64 { Int64(CACurrentMediaTime() * 1000) }

    // --- Berührungen ---------------------------------------------------------------

    private func pointerId(_ t: UITouch) -> Int {
        let key = ObjectIdentifier(t)
        if let id = ids[key] { return id }
        nextId += 1
        ids[key] = nextId
        return nextId
    }

    private func isHardware(_ t: UITouch) -> Bool {
        if #available(iOS 13.4, *) { return t.type == .indirectPointer }
        return false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if touches.contains(where: isHardware) {
            onHardwareInput()
            super.touchesBegan(touches, with: event)
            return
        }
        _ = policy.onTouch()
        let now = Self.now()
        for t in touches {
            let p = t.location(in: self)
            if let ed = editor {
                ed.begin(Float(p.x), Float(p.y), controller.safe, Self.handle)
                refreshToolbar()
            } else {
                controller.down(pointerId(t), Float(p.x), Float(p.y), now)
            }
        }
        setNeedsDisplay()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches where !isHardware(t) {
            let p = t.location(in: self)
            if let ed = editor { ed.drag(to: Float(p.x), Float(p.y), controller.safe) } else { controller.move(pointerId(t), Float(p.x), Float(p.y)) }
        }
        setNeedsDisplay()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let now = Self.now()
        for t in touches where !isHardware(t) {
            let p = t.location(in: self)
            if let ed = editor { ed.end() } else { controller.up(pointerId(t), Float(p.x), Float(p.y), now) }
            ids[ObjectIdentifier(t)] = nil
        }
        setNeedsDisplay()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        editor?.end()
        controller.cancelAll()
        touches.forEach { ids[ObjectIdentifier($0)] = nil }
        setNeedsDisplay()
    }

    // --- Zeichnen -----------------------------------------------------------------

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        if editor == nil && policy.hidden { return }
        let layout = editor?.layout ?? controller.layout
        let grabbed = sink.isGrabbed()
        if editor != nil {
            ctx.setFillColor(UIColor(white: 0, alpha: 0.4).cgColor)
            ctx.fill(bounds)
        }
        for b in layout.buttons where editor != nil || b.visible(grabbed: grabbed) {
            drawButton(ctx, b, alpha: CGFloat(editor != nil ? max(0.35, b.opacity) : b.opacity))
        }
        if let ed = editor, let sel = ed.selected {
            let r = TouchGeometry.toPx(sel, controller.safe)
            ctx.setStrokeColor(Self.accent.cgColor)
            ctx.setLineWidth(2)
            ctx.setLineDash(phase: 0, lengths: [6, 4])
            ctx.stroke(CGRect(x: CGFloat(r.x) - 3, y: CGFloat(r.y) - 3, width: CGFloat(r.w) + 6, height: CGFloat(r.h) + 6))
            ctx.setLineDash(phase: 0, lengths: [])
            let h = ed.handleBox(sel, controller.safe, Self.handle)
            ctx.setFillColor(Self.accent.cgColor)
            ctx.fill(CGRect(x: CGFloat(h.x), y: CGFloat(h.y), width: CGFloat(h.w), height: CGFloat(h.h)))
        }
    }

    private func drawButton(_ ctx: CGContext, _ b: TouchButton, alpha: CGFloat) {
        let r = TouchGeometry.toPx(b, controller.safe)
        let c = TouchGeometry.circleOf(r)
        let rect = CGRect(x: CGFloat(r.x), y: CGFloat(r.y), width: CGFloat(r.w), height: CGFloat(r.h))
        let circle = CGRect(x: CGFloat(c.cx - c.r), y: CGFloat(c.cy - c.r), width: CGFloat(c.r * 2), height: CGFloat(c.r * 2))
        let down = controller.pressed.contains(b.id)
        let latched = controller.latched.contains(b.id)
        ctx.setLineWidth(2.5)

        if b.isJoystick {
            ctx.setFillColor(Self.fill.withAlphaComponent(alpha * 0.6).cgColor)
            ctx.fillEllipse(in: circle)
            ctx.setStrokeColor(Self.text.withAlphaComponent(alpha).cgColor)
            ctx.strokeEllipse(in: circle)
            let v = controller.knobs[b.id]
            let kx = CGFloat(c.cx + (v?.x ?? 0) * c.r * 0.6)
            let ky = CGFloat(c.cy + (v?.y ?? 0) * c.r * 0.6)
            let kr = CGFloat(c.r * 0.4)
            let knobRect = CGRect(x: kx - kr, y: ky - kr, width: kr * 2, height: kr * 2)
            ctx.setFillColor(Self.knob.withAlphaComponent(max(alpha, down ? 0.9 : 0)).cgColor)
            ctx.fillEllipse(in: knobRect)
            ctx.setStrokeColor(Self.accent.withAlphaComponent(max(alpha, down ? 0.9 : 0)).cgColor)
            ctx.strokeEllipse(in: knobRect)
            return
        }
        if b.isHotbar {
            ctx.setFillColor(Self.accent.withAlphaComponent(alpha * 0.5).cgColor)
            ctx.fill(rect)
            ctx.setStrokeColor(Self.accent.withAlphaComponent(alpha).cgColor)
            ctx.setLineDash(phase: 0, lengths: [8, 5])
            ctx.stroke(rect)
            ctx.setLineDash(phase: 0, lengths: [])
            for i in 1..<9 {
                let x = rect.minX + rect.width * CGFloat(i) / 9
                ctx.move(to: CGPoint(x: x, y: rect.minY + rect.height * 0.2))
                ctx.addLine(to: CGPoint(x: x, y: rect.minY + rect.height * 0.8))
            }
            ctx.strokePath()
            return
        }

        ctx.setFillColor((down ? Self.fillDown : Self.fill).withAlphaComponent(alpha).cgColor)
        let borderColor = down ? Self.accent : latched ? Self.lamp : Self.border
        ctx.setStrokeColor(borderColor.withAlphaComponent(max(alpha, down || latched ? 1 : 0)).cgColor)
        let box: CGFloat
        if b.shape == .round {
            ctx.fillEllipse(in: circle)
            ctx.strokeEllipse(in: circle)
            box = circle.width
        } else {
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 3).cgPath
            ctx.addPath(path)
            ctx.fillPath()
            ctx.addPath(path)
            ctx.strokePath()
            box = min(rect.width, rect.height)
        }

        let textColor = Self.text.withAlphaComponent(min(1, alpha + 0.25))
        if let icon = b.icon {
            let s = box * 0.5 / CGFloat(TouchIcons.size)
            let ox = CGFloat(c.cx) - s * CGFloat(TouchIcons.size) / 2
            let oy = CGFloat(c.cy) - s * CGFloat(TouchIcons.size) / 2
            ctx.setFillColor(textColor.cgColor)
            for (x, y) in TouchIcons.pixels(icon) {
                ctx.fill(CGRect(x: ox + CGFloat(x) * s, y: oy + CGFloat(y) * s, width: s + 0.3, height: s + 0.3))
            }
            if let badge = b.badge {
                let size = max(8, min(rect.width, rect.height) * 0.22)
                let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.monospacedSystemFont(ofSize: size, weight: .bold), .foregroundColor: Self.lamp.withAlphaComponent(min(1, alpha + 0.25))]
                let str = NSAttributedString(string: badge, attributes: attrs)
                let sz = str.size()
                let bx = b.shape == .round ? CGFloat(c.cx + c.r * 0.62) : rect.maxX - 2
                let by = b.shape == .round ? CGFloat(c.cy + c.r * 0.72) : rect.maxY - 2
                str.draw(at: CGPoint(x: bx - sz.width, y: by - sz.height))
            }
        } else if let label = b.label {
            let size = max(9, min(rect.height * 0.32, rect.width * 1.6 / CGFloat(max(1, label.count))))
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.monospacedSystemFont(ofSize: size, weight: .bold), .foregroundColor: textColor]
            let str = NSAttributedString(string: label, attributes: attrs)
            let sz = str.size()
            str.draw(at: CGPoint(x: CGFloat(c.cx) - sz.width / 2, y: CGFloat(c.cy) - sz.height / 2))
        }
    }

    // --- Werkzeugleiste des Editors -------------------------------------------------

    private func s(_ key: String) -> String { OverlayStrings.get(key) }

    private func buildToolbar() {
        toolbarScroll.backgroundColor = Self.fill.withAlphaComponent(0.9)
        toolbarScroll.showsHorizontalScrollIndicator = false
        toolbarScroll.isHidden = true
        toolbar.axis = .horizontal
        toolbar.spacing = 6
        toolbar.alignment = .center
        toolbar.isLayoutMarginsRelativeArrangement = true
        toolbar.layoutMargins = UIEdgeInsets(top: 6, left: 8, bottom: 6, right: 8)
        toolbarScroll.addSubview(toolbar)
        addSubview(toolbarScroll)

        addTool(s("editor.add")) { [weak self] in self?.pickAction { a, label, icon in self?.editor?.add(a, label: label, icon: icon) } }
        selectionButtons.append(addTool(s("editor.action")) { [weak self] in self?.pickAction { a, label, icon in self?.editor?.setAction(a, label: label, icon: icon) } })
        selectionButtons.append(addTool(s("editor.shape")) { [weak self] in self?.editor?.cycleShape() })
        selectionButtons.append(addTool("−", accessibility: s("editor.smaller")) { [weak self] in self?.editor?.scale(0.9) })
        selectionButtons.append(addTool("+", accessibility: s("editor.bigger")) { [weak self] in self?.editor?.scale(1.1) })
        selectionButtons.append(addTool("◐−", accessibility: s("editor.fainter")) { [weak self] in
            if let ed = self?.editor, let b = ed.selected { ed.setOpacity(b.opacity - 0.1) }
        })
        selectionButtons.append(addTool("◐+", accessibility: s("editor.stronger")) { [weak self] in
            if let ed = self?.editor, let b = ed.selected { ed.setOpacity(b.opacity + 0.1) }
        })
        selectionButtons.append(addTool(s("editor.remove")) { [weak self] in self?.editor?.remove() })
        addTool(s("editor.grid")) { [weak self] in if let ed = self?.editor { ed.grid.toggle() } }
        addTool(s("editor.cancel")) { [weak self] in self?.setEditing(false) }
        addTool(s("editor.save"), primary: true) { [weak self] in self?.save() }
    }

    @discardableResult
    private func addTool(_ title: String, accessibility: String? = nil, primary: Bool = false, _ action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.setTitleColor(Self.text, for: .normal)
        b.titleLabel?.font = .boldSystemFont(ofSize: 14)
        b.backgroundColor = primary ? Self.accent : Self.knob
        b.layer.cornerRadius = 8
        b.contentEdgeInsets = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        b.accessibilityLabel = accessibility ?? title
        b.addAction(UIAction { [weak self] _ in
            action()
            self?.refreshToolbar()
            self?.setNeedsDisplay()
        }, for: .touchUpInside)
        toolbar.addArrangedSubview(b)
        return b
    }

    private func refreshToolbar() {
        let has = editor?.selected != nil
        selectionButtons.forEach { $0.isEnabled = has; $0.alpha = has ? 1 : 0.4 }
    }

    private func presenter() -> UIViewController? {
        var vc = window?.rootViewController
        while let next = vc?.presentedViewController { vc = next }
        return vc
    }

    private func pickAction(_ done: @escaping (TouchAction, String?, String?) -> Void) {
        let sheet = UIAlertController(title: s("editor.pickAction"), message: nil, preferredStyle: .actionSheet)
        for p in TouchActionPresets.all {
            sheet.addAction(UIAlertAction(title: s("preset.\(p.key)"), style: .default) { [weak self] _ in
                done(p.action, p.label, p.icon)
                self?.refreshToolbar()
                self?.setNeedsDisplay()
            })
        }
        sheet.addAction(UIAlertAction(title: s("editor.otherKey"), style: .default) { [weak self] _ in
            self?.pickKey { code in done(.key(code, chord: []), TouchActionPresets.keyName(code), nil) }
        })
        sheet.addAction(UIAlertAction(title: s("editor.cancel"), style: .cancel))
        sheet.popoverPresentationController?.sourceView = toolbarScroll
        presenter()?.present(sheet, animated: true)
    }

    private func pickKey(_ done: @escaping (Int) -> Void) {
        let sheet = UIAlertController(title: s("editor.pickKey"), message: nil, preferredStyle: .actionSheet)
        for code in TouchActionPresets.keys {
            sheet.addAction(UIAlertAction(title: TouchActionPresets.keyName(code), style: .default) { [weak self] _ in
                done(code)
                self?.refreshToolbar()
                self?.setNeedsDisplay()
            })
        }
        sheet.addAction(UIAlertAction(title: s("editor.cancel"), style: .cancel))
        sheet.popoverPresentationController?.sourceView = toolbarScroll
        presenter()?.present(sheet, animated: true)
    }

    private func save() {
        guard let ed = editor else { return }
        do {
            let layout = try ed.result()
            try store.save(layout)
            controller.setLayout(layout)
            setEditing(false)
        } catch {
            let alert = UIAlertController(title: s("editor.saveFailed"), message: nil, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            presenter()?.present(alert, animated: true)
        }
    }
}
#endif
