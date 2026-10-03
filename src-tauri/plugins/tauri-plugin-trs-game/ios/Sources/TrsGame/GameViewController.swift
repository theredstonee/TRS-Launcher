// TRS Launcher – Vollbild-Spielansicht (quer, Home-Balken aus). Zeigt bei fehlendem JIT erst die
// Hilfe, startet dann die JVM und reicht Eingaben an die Engine weiter (GameInput).
// Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only

import GameController
import Tauri
import UIKit

/// Startdaten vom Rust-Teil (src/ios/args.rs, `EngineLaunch`).
struct LaunchRequest: Decodable {
  let session: String
  let javaHome: String
  let javaMajor: Int
  let argv: [String]
  let env: [String: String]
  let gameDir: String
  let xmxMb: Int
  let windowWidth: Int
  let windowHeight: Int
  let renderer: String
  let metalLayer: Bool
  let waitForJit: Bool
  let jitHelp: String
  /// Touch-Layout und sein Ordner (`<Launcher-Daten>/controls`), fehlt bei älteren Kernen.
  let touchProfile: String?
  let controlsDir: String?
}

/// Ereignis an Rust (src/ios/session.rs, `EngineEvent`).
struct EngineEventOut: Encodable {
  let type: String
  var line: String? = nil
  var code: Int? = nil
  var logTail: String? = nil
  var reason: String? = nil
}

/// Eine laufende Sitzung; die Engine-Rückrufe (C) finden sie über `current`.
final class GameSession {
  static var current: GameSession?

  let request: LaunchRequest
  private let events: Channel

  init(request: LaunchRequest, events: Channel) {
    self.request = request
    self.events = events
  }

  func send(_ event: EngineEventOut) {
    try? events.send(event)
  }

  func engineExited(code: Int) {
    send(EngineEventOut(type: "exited", code: code))
    guard code != 0 else { return }
    DispatchQueue.main.async {
      if let vc = GameViewController.current {
        vc.showCrash(code: code)
      } else {
        Engine.shared?.finishExit()
      }
    }
  }
}

// C-Rückrufe der Engine (ohne Kontext, daher über GameSession.current).
private let engineLogCallback: Engine.LogCallback = { line in
  guard let line = line else { return }
  let text = String(cString: line)
  GameSession.current?.send(EngineEventOut(type: "log", line: text))
  // TRS Client im Touch-Modus wünscht die Tastatur (docs/touch-mode.md, Log-Zeile je Änderung).
  if text.contains("[TRS-Touch] keyboard.show") || text.contains("[TRS-Touch] keyboard.hide") {
    let show = text.contains("[TRS-Touch] keyboard.show")
    DispatchQueue.main.async { GameViewController.current?.showKeyboard(show: show) }
  }
}

private let engineExitCallback: Engine.CodeCallback = { code in
  GameSession.current?.engineExited(code: Int(code))
}

private let engineGrabCallback: Engine.CodeCallback = { grabbing in
  DispatchQueue.main.async {
    GameViewController.current?.grabChanged(grabbing != 0)
  }
}

private let engineFrameCallback: Engine.VoidFn = {
  GameSession.current?.send(EngineEventOut(type: "running"))
}

/// Zeichenfläche: CAMetalLayer (ANGLE/MobileGlues/gl4es) bzw. CALayer (Zink über OSMesa).
final class GameSurfaceView: UIView {
  static var useMetalLayer = true

  override class var layerClass: AnyClass {
    useMetalLayer ? CAMetalLayer.self : CALayer.self
  }

  override init(frame: CGRect) {
    super.init(frame: frame)
    layer.drawsAsynchronously = true
    layer.isOpaque = true
    isMultipleTouchEnabled = true
  }

  required init?(coder: NSCoder) {
    fatalError("init(coder:) wird nicht benutzt")
  }
}

public final class GameViewController: UIViewController, GameInput {
  /// Die gerade sichtbare Spielansicht.
  public private(set) static weak var current: GameViewController?
  /// Vom Touch-Overlay gesetzt: baut die Steuerung über dem Spiel (bekommt GameInput).
  public static var overlayFactory: ((GameInput) -> UIView)?

  private let engine: Engine
  private let session: GameSession
  private lazy var surface: GameSurfaceView = {
    GameSurfaceView.useMetalLayer = self.session.request.metalLayer
    return GameSurfaceView(frame: .zero)
  }()
  private let keyboard = KeyboardProxyView()
  private var jitHelp: JitHelpView?
  private var started = false
  private var observers: [NSObjectProtocol] = []
  /// Eingebaute Touch-Steuerung (ohne eigene `overlayFactory`).
  private var touchOverlay: TouchOverlayHandle?

  init(engine: Engine, session: GameSession) {
    self.engine = engine
    self.session = session
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .fullScreen
  }

  required init?(coder: NSCoder) {
    fatalError("init(coder:) wird nicht benutzt")
  }

  deinit {
    observers.forEach { NotificationCenter.default.removeObserver($0) }
  }

  // MARK: Darstellung

  public override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
  public override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .landscapeRight }
  public override var prefersHomeIndicatorAutoHidden: Bool { true }
  public override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }
  public override var prefersStatusBarHidden: Bool { true }
  @available(iOS 14.0, *)
  public override var prefersPointerLocked: Bool { started && engine.isGrabbing }

  public override func loadView() {
    let root = UIView()
    root.backgroundColor = .black
    view = root
  }

  public override func viewDidLoad() {
    super.viewDidLoad()
    GameViewController.current = self
    surface.frame = view.bounds
    surface.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    view.addSubview(surface)
    engine.setSurface(surface)

    keyboard.input = self
    view.addSubview(keyboard)

    if let factory = GameViewController.overlayFactory {
      let overlay = factory(self)
      overlay.frame = view.bounds
      overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      view.addSubview(overlay)
    } else {
      // Touch-Steuerung (Sources/Overlay) mit dem Layout der Instanz.
      let request = session.request
      let dir = request.controlsDir.map { URL(fileURLWithPath: $0, isDirectory: true) }
        ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("controls", isDirectory: true)
      touchOverlay = TouchOverlay.shared.attach(to: view, input: self, config: TouchOverlayConfig(controlsDir: dir, profileId: request.touchProfile, insets: nil))
    }

    let center = NotificationCenter.default
    observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
      if self?.started == true { self?.engine.pauseIfNeeded() }
    })
    observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
      self?.jitHelp?.checkNow()
    })
    if #available(iOS 14.0, *) {
      observers.append(center.addObserver(forName: .GCMouseDidConnect, object: nil, queue: .main) { [weak self] note in
        if let mouse = note.object as? GCMouse { self?.attach(mouse: mouse) }
      })
      GCMouse.mice().forEach { attach(mouse: $0) }
    }
  }

  public override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    updateWindowSize()
  }

  public override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)
    keyboard.becomeFirstResponder()
    if session.request.waitForJit && !engine.jitEnabled {
      showJitHelp(txm: session.request.jitHelp == "txm")
    } else {
      startEngine()
    }
  }

  private var scale: CGFloat {
    surface.layer.contentsScale
  }

  private func updateWindowSize() {
    let screenScale = view.window?.screen.scale ?? UIScreen.main.scale
    surface.layer.contentsScale = screenScale
    let width = Int((view.bounds.width * screenScale).rounded())
    let height = Int((view.bounds.height * screenScale).rounded())
    guard width > 0, height > 0 else { return }
    // Querformat, gerade Zahlen (wie Amethyst bei 100 %).
    engine.setWindowSize(physicalWidth: width, physicalHeight: height, width: width - width % 2, height: height - height % 2)
  }

  // MARK: JIT

  private func showJitHelp(txm: Bool) {
    session.send(EngineEventOut(type: "jitWaiting"))
    let help = JitHelpView(txm: txm) { [weak self] in self?.engine.jitEnabled ?? false }
    help.frame = view.bounds
    help.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    help.onReady = { [weak self] in
      guard let self = self else { return }
      self.jitHelp?.removeFromSuperview()
      self.jitHelp = nil
      self.session.send(EngineEventOut(type: "jitReady"))
      self.startEngine()
    }
    help.onCancel = { [weak self] in
      self?.session.send(EngineEventOut(type: "cancelled"))
      self?.close()
    }
    view.addSubview(help)
    jitHelp = help
    help.startPolling()
  }

  // MARK: Start

  private func startEngine() {
    guard !started else { return }
    started = true
    updateWindowSize()
    engine.setCallbacks(log: engineLogCallback, exit: engineExitCallback, grab: engineGrabCallback, firstFrame: engineFrameCallback)
    session.send(EngineEventOut(type: "starting"))
    let request = session.request
    let engine = self.engine
    DispatchQueue.global(qos: .userInitiated).async {
      let error = engine.launch(request)
      DispatchQueue.main.async { [weak self] in
        guard let self = self, let error = error else { return }
        self.started = false
        if error == "jitRequired" || error == "jitScript" {
          // JIT ging verloren oder das TXM-Skript fehlt: zurück zur Hilfe.
          self.showJitHelp(txm: error == "jitScript" || request.jitHelp == "txm")
          return
        }
        self.session.send(EngineEventOut(type: "failed", reason: error))
        self.showFailure(error)
      }
    }
  }

  private func showFailure(_ reason: String) {
    let alert = UIAlertController(title: Strings.startFailedTitle, message: Strings.startFailed(reason), preferredStyle: .alert)
    alert.addAction(UIAlertAction(title: Strings.back, style: .default) { [weak self] _ in self?.close() })
    present(alert, animated: true)
  }

  func showCrash(code: Int) {
    let alert = UIAlertController(title: Strings.crashTitle, message: Strings.crashMessage(code: code), preferredStyle: .alert)
    alert.addAction(UIAlertAction(title: Strings.copyLog, style: .default) { _ in
      let path = (DeviceProbe.documentsPath() as NSString).appendingPathComponent("trs-engine.log")
      if let text = try? String(contentsOfFile: path, encoding: .utf8) {
        UIPasteboard.general.string = String(text.suffix(200_000))
      }
      Engine.shared?.finishExit()
    })
    alert.addAction(UIAlertAction(title: Strings.close, style: .cancel) { _ in
      Engine.shared?.finishExit()
    })
    (presentedViewController ?? self).present(alert, animated: true)
  }

  private func close() {
    jitHelp?.stopPolling()
    engine.setSurface(nil)
    GameSession.current = nil
    dismiss(animated: true)
  }

  func grabChanged(_ grabbing: Bool) {
    if #available(iOS 14.0, *) {
      setNeedsUpdateOfPrefersPointerLocked()
    }
    NotificationCenter.default.post(name: .trsGameGrabChanged, object: self)
  }

  // MARK: Maus (Hardware)

  @available(iOS 14.0, *)
  private func attach(mouse: GCMouse) {
    guard let input = mouse.mouseInput else { return }
    input.mouseMovedHandler = { [weak self] _, dx, dy in
      DispatchQueue.main.async { self?.moveMouseRelative(dx: dx, dy: -dy) }
    }
    input.leftButton.pressedChangedHandler = { [weak self] _, _, pressed in
      DispatchQueue.main.async { self?.sendMouseButton(button: 0, down: pressed) }
    }
    input.rightButton?.pressedChangedHandler = { [weak self] _, _, pressed in
      DispatchQueue.main.async { self?.sendMouseButton(button: 1, down: pressed) }
    }
    input.middleButton?.pressedChangedHandler = { [weak self] _, _, pressed in
      DispatchQueue.main.async { self?.sendMouseButton(button: 2, down: pressed) }
    }
    input.scroll.valueChangedHandler = { [weak self] _, x, y in
      DispatchQueue.main.async { self?.scroll(dx: x, dy: y) }
    }
  }

  // MARK: GameInput

  public func sendKey(glfwKey: Int, scancode: Int, down: Bool, mods: Int) {
    guard started else { return }
    engine.key(glfwKey, scancode: scancode, down: down, mods: mods)
  }

  public func sendChar(codepoint: Int) {
    guard started, codepoint > 0, codepoint <= 0x10FFFF else { return }
    engine.char(UInt32(codepoint))
  }

  public func sendMouseButton(button: Int, down: Bool) {
    guard started else { return }
    engine.mouseButton(button, down: down)
  }

  public func moveMouseRelative(dx: Float, dy: Float) {
    guard started else { return }
    engine.moveRelative(dx: dx * Float(scale), dy: dy * Float(scale))
  }

  public func moveMouseAbsolute(x: Float, y: Float) {
    guard started else { return }
    engine.moveAbsolute(x: x * Float(scale), y: y * Float(scale))
  }

  public func scroll(dx: Float, dy: Float) {
    guard started else { return }
    engine.scroll(dx: dx, dy: dy)
  }

  public func isGrabbed() -> Bool {
    started && engine.isGrabbing
  }

  public func showKeyboard(show: Bool) {
    keyboard.setSoftwareKeyboard(visible: show)
  }
}

/// Unsichtbarer Empfänger für Text (Bildschirm- und Hardware-Tastatur). Die Bildschirmtastatur
/// erscheint nur auf Anfrage; sonst verhindert eine leere `inputView` sie.
final class KeyboardProxyView: UIView, UIKeyInput {
  weak var input: GameInput?
  private var softwareVisible = false
  private let emptyInput = UIView(frame: .zero)
  /// Gerade gedrückte Hardware-Tasten (GLFW), damit Rücktaste/Enter nicht doppelt kommen.
  private var hardwareDown = Set<Int>()

  override var canBecomeFirstResponder: Bool { true }
  override var inputView: UIView? { softwareVisible ? nil : emptyInput }

  var autocorrectionType: UITextAutocorrectionType = .no
  var autocapitalizationType: UITextAutocapitalizationType = .none
  var spellCheckingType: UITextSpellCheckingType = .no
  var smartQuotesType: UITextSmartQuotesType = .no
  var smartDashesType: UITextSmartDashesType = .no
  var keyboardType: UIKeyboardType = .asciiCapable
  var returnKeyType: UIReturnKeyType = .done

  func setSoftwareKeyboard(visible: Bool) {
    softwareVisible = visible
    if !isFirstResponder {
      becomeFirstResponder()
    }
    reloadInputViews()
  }

  var hasText: Bool { true }

  func insertText(_ text: String) {
    for scalar in text.unicodeScalars {
      if scalar == "\n" || scalar == "\r" {
        if !hardwareDown.contains(GlfwKey.enter) { tap(GlfwKey.enter) }
      } else {
        input?.sendChar(codepoint: Int(scalar.value))
      }
    }
  }

  func deleteBackward() {
    if !hardwareDown.contains(GlfwKey.backspace) { tap(GlfwKey.backspace) }
  }

  private func tap(_ key: Int) {
    input?.sendKey(glfwKey: key, scancode: 0, down: true, mods: 0)
    input?.sendKey(glfwKey: key, scancode: 0, down: false, mods: 0)
  }

  override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
    if #available(iOS 13.4, *) {
      for press in presses {
        guard let key = press.key, let glfw = KeyMapping.glfwKey(key.keyCode) else { continue }
        hardwareDown.insert(glfw)
        input?.sendKey(glfwKey: glfw, scancode: key.keyCode.rawValue, down: true, mods: KeyMapping.glfwMods(key.modifierFlags))
      }
    }
    super.pressesBegan(presses, with: event)
  }

  override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
    releaseKeys(presses)
    super.pressesEnded(presses, with: event)
  }

  override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
    releaseKeys(presses)
    super.pressesCancelled(presses, with: event)
  }

  private func releaseKeys(_ presses: Set<UIPress>) {
    if #available(iOS 13.4, *) {
      for press in presses {
        guard let key = press.key, let glfw = KeyMapping.glfwKey(key.keyCode) else { continue }
        // Erst nach dem Text-Ereignis vergessen (deleteBackward/insertText kommen danach).
        DispatchQueue.main.async { self.hardwareDown.remove(glfw) }
        input?.sendKey(glfwKey: glfw, scancode: key.keyCode.rawValue, down: false, mods: KeyMapping.glfwMods(key.modifierFlags))
      }
    }
  }
}
