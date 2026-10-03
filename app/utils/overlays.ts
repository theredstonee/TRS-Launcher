import { getCurrentInstance, onBeforeUnmount, onMounted } from 'vue'

/**
 * Offene Dialoge, Sheets und Vollbild-Ansichten (oberste zuletzt). Die Zurück-Taste
 * am Handy schließt zuerst die oberste – erst danach geht es eine Seite zurück.
 */
const stack: { id: number; close: () => void }[] = []
let nextId = 1

export function pushOverlay(close: () => void): number {
  const id = nextId++
  stack.push({ id, close })
  return id
}

export function removeOverlay(id: number) {
  const i = stack.findIndex((o) => o.id === id)
  if (i >= 0) stack.splice(i, 1)
}

export function overlayCount(): number {
  return stack.length
}

/** Schließt die oberste Ebene; `false`, wenn nichts offen war. */
export function closeTopOverlay(): boolean {
  const top = stack[stack.length - 1]
  if (!top) return false
  top.close()
  // Der Schließer entfernt sich normalerweise selbst (beim Aushängen) – sicher ist sicher.
  removeOverlay(top.id)
  return true
}

/** In einer Komponente: solange sie eingehängt ist, schließt „Zurück“ sie. */
export function useOverlay(close: () => void) {
  if (!getCurrentInstance()) return
  let id: number | null = null
  onMounted(() => (id = pushOverlay(close)))
  onBeforeUnmount(() => {
    if (id !== null) removeOverlay(id)
  })
}

/** Hauptbereiche der Tab-Leiste: dort führt „Zurück“ zur Startseite, auf der Startseite beendet es die App. */
export const MOBILE_TAB_ROOTS = ['/', '/instances', '/browse', '/social'] as const

export type BackAction = 'close' | 'back' | 'home' | 'exit'

/** Was die Zurück-Taste (Android) gerade tun soll. */
export function backAction(state: { overlays: number; path: string; canGoBack: boolean }): BackAction {
  if (state.overlays > 0) return 'close'
  if (state.path === '/') return 'exit'
  const isTabRoot = (MOBILE_TAB_ROOTS as readonly string[]).includes(state.path)
  if (!isTabRoot && state.canGoBack) return 'back'
  return 'home'
}
