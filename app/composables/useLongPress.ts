/**
 * Langer Druck mit dem Finger (Handy) – Ersatz für Aktionen, die am Desktop erst beim
 * Überfahren oder per Rechtsklick erscheinen. Handler per `v-on="longPress"` binden;
 * am Desktop tun sie nichts (dort gibt es Hover und Rechtsklick).
 */
export function useLongPress(onLongPress: (e: PointerEvent) => void) {
  let timer: ReturnType<typeof setTimeout> | null = null
  let start: { x: number; y: number } | null = null
  let fired = false

  function clear() {
    if (timer) clearTimeout(timer)
    timer = null
    start = null
  }
  function down(e: PointerEvent) {
    if (!mobileUi.value || e.pointerType === 'mouse') return
    fired = false
    start = { x: e.clientX, y: e.clientY }
    timer = setTimeout(() => {
      fired = true
      timer = null
      navigator.vibrate?.(12)
      onLongPress(e)
    }, LONG_PRESS_MS)
  }
  function move(e: PointerEvent) {
    if (start && longPressCancelled(e.clientX - start.x, e.clientY - start.y)) clear()
  }
  /** Nach einem langen Druck den folgenden Klick schlucken. */
  function click(e: MouseEvent) {
    if (!fired) return
    fired = false
    e.preventDefault()
    e.stopPropagation()
  }
  /** Nach einem langen Druck keine nachgeahmten Maus-Ereignisse (mousedown/click) mehr – sie schlössen das gerade geöffnete Sheet. */
  function touchend(e: TouchEvent) {
    if (fired && e.cancelable) e.preventDefault()
  }
  /** Android öffnet bei langem Druck sonst das System-Kontextmenü. */
  function contextmenu(e: MouseEvent) {
    if (mobileUi.value && (timer || fired)) e.preventDefault()
  }
  onBeforeUnmount(clear)
  return { pointerdown: down, pointermove: move, pointerup: clear, pointercancel: clear, pointerleave: clear, click, touchend, contextmenu }
}
