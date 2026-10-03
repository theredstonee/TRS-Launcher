import type { Ref } from 'vue'
import type { BigAction, NavDirection } from '~/utils/bigPicture'

export interface BigInputHandlers {
  /** B/Esc ohne fremden Dialog; `false` = nichts zu tun. */
  back: () => boolean
  /** Y/C: Optionen zum fokussierten Element. */
  context: (el: HTMLElement | null) => void
  /** Start/M: Menü. */
  menu: () => void
  /** LB/RB bzw. Q/E: Bereich wechseln. */
  section: (delta: 1 | -1) => void
}

const FOCUSABLE = 'button, a[href], input, select, textarea, [tabindex]'
const NATIVE = 'button, a[href], input, select, textarea, summary'

/**
 * Steuerung der Big-Picture-Ansicht: Gamepad (Gamepad-API, je Frame abgefragt),
 * Pfeiltasten/Enter/Esc und räumliche Navigation zwischen allen fokussierbaren
 * Elementen. Oberste Ebene ist ein fremder Dialog (`aria-modal`), sonst die
 * oberste eigene Ebene (`data-bp-layer`), sonst die ganze Ansicht.
 */
export function useBigPictureInput(root: Ref<HTMLElement | null>, handlers: BigInputHandlers) {
  const store = useBigPictureStore()
  const { reduced } = useMotion()
  /** `nav` = Controller/Tastatur (deutlicher Fokusrahmen), `pointer` = Maus/Touch. */
  const mode = ref<'nav' | 'pointer'>('nav')
  const reader = createPadReader()
  let frame = 0

  function foreignModal(): HTMLElement | null {
    const list = document.querySelectorAll<HTMLElement>('[aria-modal="true"]:not([data-bp-layer])')
    return list[list.length - 1] ?? null
  }

  function scope(): HTMLElement | null {
    const modal = foreignModal()
    if (modal) return modal
    const el = root.value
    if (!el) return null
    const layers = el.querySelectorAll<HTMLElement>('[data-bp-layer]')
    return layers[layers.length - 1] ?? el
  }

  function focusables(within: HTMLElement): HTMLElement[] {
    return [...within.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
      (el) =>
        el.tabIndex >= 0 &&
        !(el as HTMLButtonElement).disabled &&
        !el.closest('[data-bp-skip], [inert]') &&
        el.getClientRects().length > 0,
    )
  }

  function focusEl(el: HTMLElement) {
    el.focus({ preventScroll: true })
    el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: reduced.value ? 'auto' : 'smooth' })
  }

  /** Erstes Element der obersten Ebene (`data-bp-autofocus` zuerst, sonst oben links). */
  function focusFirst() {
    const s = scope()
    if (!s) return
    const list = focusables(s)
    const preferred = list.find((el) => el.closest('[data-bp-autofocus]'))
    const first = preferred ?? list[firstInReadingOrder(list.map((el) => el.getBoundingClientRect()))]
    if (first) focusEl(first)
  }

  function current(within: HTMLElement): HTMLElement | null {
    const el = document.activeElement
    return el instanceof HTMLElement && el !== within && within.contains(el) ? el : null
  }

  function isRange(el: Element | null): el is HTMLInputElement {
    return el instanceof HTMLInputElement && el.type === 'range'
  }

  /** Schieberegler mit links/rechts verstellen (wie die Pfeiltasten). */
  function adjust(range: HTMLInputElement, dir: 'left' | 'right') {
    if (dir === 'right') range.stepUp()
    else range.stepDown()
    range.dispatchEvent(new Event('input', { bubbles: true }))
    range.dispatchEvent(new Event('change', { bubbles: true }))
  }

  function move(dir: NavDirection) {
    const s = scope()
    if (!s) return
    const from = current(s)
    if (!from) return focusFirst()
    if (isRange(from) && (dir === 'left' || dir === 'right')) return adjust(from, dir)
    const others = focusables(s).filter((el) => el !== from)
    const i = spatialNext(
      from.getBoundingClientRect(),
      others.map((el) => el.getBoundingClientRect()),
      dir,
    )
    if (i >= 0) focusEl(others[i]!)
  }

  function confirm() {
    const s = scope()
    if (!s) return
    const el = current(s)
    if (!el) return focusFirst()
    if (!isRange(el)) el.click()
  }

  function back() {
    // Fremde Dialoge schließen sich selbst mit Escape (BaseDialog).
    if (foreignModal()) {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      return
    }
    handlers.back()
  }

  function run(action: BigAction) {
    mode.value = 'nav'
    switch (action) {
      case 'up':
      case 'down':
      case 'left':
      case 'right':
        return move(action)
      case 'confirm':
        return confirm()
      case 'back':
        return back()
      case 'context':
        if (!foreignModal()) handlers.context(current(root.value ?? document.body))
        return
      case 'menu':
        if (!foreignModal()) handlers.menu()
        return
      case 'prevSection':
      case 'nextSection':
        if (!foreignModal() && !root.value?.querySelector('[data-bp-layer]')) handlers.section(action === 'nextSection' ? 1 : -1)
    }
  }

  // --- Gamepad ---------------------------------------------------------------------
  function readPads(): Gamepad[] {
    try {
      return [...(navigator.getGamepads?.() ?? [])].filter((p): p is Gamepad => !!p && p.connected)
    } catch {
      return []
    }
  }

  function poll(now: number) {
    const pads = readPads()
    if (!pads.length) {
      frame = 0
      reader.reset()
      return
    }
    frame = requestAnimationFrame(poll)
    // Im Hintergrund (Spiel läuft, anderes Fenster vorn) nichts auslösen.
    if (document.visibilityState !== 'visible' || !document.hasFocus()) {
      reader.reset()
      return
    }
    const snapshot = mergePads(pads.map((p) => ({ buttons: p.buttons.map((b) => b.pressed), axes: p.axes })))
    const actions = reader.update(snapshot, now)
    if (!actions.length) return
    const used = pads.find((p) => p.buttons.some((b) => b.pressed) || p.axes.some((a) => Math.abs(a) >= 0.5)) ?? pads[0]!
    store.controller = detectController(used.id)
    for (const action of actions) run(action)
  }

  function startPolling() {
    if (!frame) frame = requestAnimationFrame(poll)
  }

  /** Neuer Controller: Hinweise sofort passend zeigen und abfragen. */
  function onConnect(e: GamepadEvent) {
    store.controller = detectController(e.gamepad.id)
    mode.value = 'nav'
    startPolling()
  }

  // --- Tastatur und Zeiger ------------------------------------------------------------
  function onKey(e: KeyboardEvent) {
    if (e.defaultPrevented || foreignModal()) return
    const action = keyAction(e)
    if (!action) return
    const target = document.activeElement
    // Native Bedienelemente behalten ihre Tasten: Enter/Leertaste klicken selbst,
    // Schieberegler nehmen links/rechts.
    if (action === 'confirm' && target instanceof HTMLElement && target.matches(NATIVE)) {
      mode.value = 'nav'
      store.controller = 'keyboard'
      return
    }
    if (isRange(target) && (action === 'left' || action === 'right')) return
    e.preventDefault()
    store.controller = 'keyboard'
    run(action)
  }

  function onPointer(e: PointerEvent) {
    // Scrollen erzeugt Bewegungs-Events ohne Bewegung – die zählen nicht.
    if (e.type === 'pointermove' && !e.movementX && !e.movementY) return
    mode.value = 'pointer'
  }

  onMounted(() => {
    window.addEventListener('keydown', onKey)
    window.addEventListener('pointerdown', onPointer, { passive: true })
    window.addEventListener('pointermove', onPointer, { passive: true })
    window.addEventListener('gamepadconnected', onConnect)
    // Schon verbunden (Chromium meldet Pads erst nach dem ersten Druck).
    const pads = readPads()
    if (pads.length) {
      store.controller = detectController(pads[0]!.id)
      startPolling()
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKey)
    window.removeEventListener('pointerdown', onPointer)
    window.removeEventListener('pointermove', onPointer)
    window.removeEventListener('gamepadconnected', onConnect)
    if (frame) cancelAnimationFrame(frame)
    frame = 0
  })

  return { mode, focusFirst, focusEl }
}
