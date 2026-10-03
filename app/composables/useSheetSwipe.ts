/**
 * Bottom-Sheet am Handy nach unten wegwischen: am Griff/Kopf ziehen, ab einer
 * Strecke oder mit Schwung schließt es. `style` an das Sheet binden,
 * `handlers` an den Griff.
 */
export function useSheetSwipe(close: () => void) {
  const offset = ref(0)
  let startY: number | null = null
  let startT = 0
  let pointer: number | null = null
  let captured = false

  function down(e: PointerEvent) {
    if (!mobileUi.value || e.button > 0) return
    // Knöpfe im Kopf (Schließen, Aktionen) bekommen ihren Klick – dort beginnt kein Wisch.
    if ((e.target as Element | null)?.closest?.('button, a, input, select, textarea, [role="button"]')) return
    startY = e.clientY
    startT = performance.now()
    pointer = e.pointerId
    captured = false
  }
  function move(e: PointerEvent) {
    if (startY === null || e.pointerId !== pointer) return
    const dy = Math.max(0, e.clientY - startY)
    // Erst festhalten, wenn sich der Finger wirklich bewegt (sonst gingen Tipps verloren).
    if (!captured && dy > 4) {
      captured = true
      ;(e.currentTarget as HTMLElement | null)?.setPointerCapture?.(e.pointerId)
    }
    offset.value = dy
  }
  function up(e: PointerEvent) {
    if (startY === null || e.pointerId !== pointer) return
    const dy = Math.max(0, e.clientY - startY)
    const speed = dy / Math.max(1, performance.now() - startT)
    startY = null
    pointer = null
    captured = false
    if (sheetSwipeCloses(dy, speed)) close()
    offset.value = 0
  }

  const style = computed(() =>
    offset.value > 0 ? { transform: `translateY(${offset.value}px)`, transition: 'none' } : undefined,
  )
  return { offset, style, handlers: { pointerdown: down, pointermove: move, pointerup: up, pointercancel: up } }
}
