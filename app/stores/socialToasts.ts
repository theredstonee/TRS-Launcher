import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { z } from 'zod'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können.
import { backend } from '../utils/backend'
import { playNotificationSound } from '../utils/sound'
import {
  type SocialPrefs,
  type SocialToast,
  type SocialToastKind,
  decide,
  defaultSocialPrefs,
  pushSocialToast,
  toastDuration,
} from '../utils/socialToasts'

let nextId = 1

// Benachrichtigungen aus „Sozial“ (neue Nachricht, Einladung, Anfrage,
// Umhang-Angebot, „ist online“): eigene Liste neben den normalen Toasts, mit
// Gesicht, Aktionen und Schnellantwort. Ob etwas erscheint, entscheidet
// `decide` (Einstellungen, Nicht stören, Vollbild, stummgeschaltet, Spiel mit
// TRS Client läuft …) – hier laufen alle Sozial-Hinweise des Launchers durch.
export const useSocialToasts = defineStore('socialToasts', () => {
  const items = ref<SocialToast[]>([])
  const prefs = ref<SocialPrefs>({ ...defaultSocialPrefs })
  /** Instanzen, deren Spiel gerade mit verbundenem TRS Client läuft (Kern: `trs-client-linked`). */
  const gameClients = ref<string[]>([])
  /** Der TRS Client zeigt die Hinweise im Spiel – der Launcher schweigt dazu. */
  const clientInGame = computed(() => gameClients.value.length > 0)
  let watching = false
  const timers = new Map<number, ReturnType<typeof setTimeout>>()
  /** Pausiert (Maus darüber / Schnellantwort offen). */
  const held = new Set<number>()

  function setPrefs(next: Partial<SocialPrefs> | null | undefined) {
    prefs.value = { ...defaultSocialPrefs, ...(next ?? {}) }
  }

  function setGameClients(ids: readonly string[]) {
    gameClients.value = [...ids]
  }

  /** Einmal: verbundene Spiele vom Kern verfolgen (Link steht/bricht ab, Spielende). */
  async function watchGameClients() {
    if (watching || !isTauri()) return
    watching = true
    await listen('trs-client-linked', (event) => {
      const parsed = z.array(z.string()).safeParse(event.payload)
      if (parsed.success) setGameClients(parsed.data)
    })
    try {
      setGameClients(await backend.social.gameClients())
    } catch {
      // Älterer Kern: Launcher meldet wie bisher.
    }
  }

  function clearTimer(id: number) {
    const timer = timers.get(id)
    if (timer) clearTimeout(timer)
    timers.delete(id)
  }

  function schedule(toast: SocialToast) {
    clearTimer(toast.id)
    if (held.has(toast.id)) return
    timers.set(
      toast.id,
      setTimeout(() => dismiss(toast.id), toastDuration(prefs.value, toast.actions.length > 0 || !!toast.reply)),
    )
  }

  function dismiss(id: number) {
    clearTimer(id)
    held.delete(id)
    items.value = items.value.filter((t) => t.id !== id)
  }

  function clear() {
    for (const id of [...timers.keys()]) clearTimer(id)
    held.clear()
    items.value = []
  }

  /** Maus darüber / Eingabe aktiv: nicht verschwinden lassen. */
  function hold(id: number, value: boolean) {
    const toast = items.value.find((t) => t.id === id)
    if (!toast) return
    if (value) {
      held.add(id)
      clearTimer(id)
    } else {
      held.delete(id)
      schedule(toast)
    }
  }

  async function fullscreen(): Promise<boolean> {
    if (!prefs.value.quietInFullscreen) return false
    try {
      return await backend.social.quietHours()
    } catch {
      return false
    }
  }

  /**
   * Benachrichtigung anbieten. `looking` = der Nutzer sieht die Unterhaltung
   * gerade, `muted` = Unterhaltung stummgeschaltet, `preview` = Vorschau aus den
   * Einstellungen (kommt auch, während ein Spiel mit TRS Client läuft).
   */
  async function notify(
    kind: SocialToastKind,
    toast: Omit<SocialToast, 'id' | 'count' | 'kind'>,
    context: { looking?: boolean; muted?: boolean; preview?: boolean } = {},
  ): Promise<boolean> {
    const doc = typeof document === 'undefined' ? null : document
    const clientInGameNow = clientInGame.value && !context.preview
    const delivery = decide(kind, prefs.value, {
      focused: doc ? doc.hasFocus() : true,
      visible: doc ? doc.visibilityState === 'visible' : true,
      // Im Spiel entscheidet der TRS Client – Vollbild muss dann nicht erst gefragt werden.
      fullscreen: clientInGameNow ? false : await fullscreen(),
      looking: context.looking ?? false,
      muted: context.muted ?? false,
      clientInGame: clientInGameNow,
    })
    if (!delivery.toast) return false
    const id = nextId++
    const result = pushSocialToast(items.value, { ...toast, id, kind })
    for (const dropped of result.dropped) clearTimer(dropped)
    if (result.replaced !== null) {
      clearTimer(result.replaced)
      held.delete(result.replaced)
    }
    items.value = result.list
    const added = items.value.find((t) => t.id === id)
    if (added) schedule(added)
    if (delivery.sound) playNotificationSound()
    if (delivery.native) void backend.social.notifyNative(toast.title, toast.body).catch(() => {})
    return true
  }

  /**
   * Schlichter Sozial-Hinweis ohne Gesicht/Aktionen (z. B. „aus der Welt entfernt“)
   * als normaler Toast – ebenfalls still, solange ein Spiel mit TRS Client läuft.
   */
  function notice(text: string): boolean {
    if (clientInGame.value) return false
    useToasts().info(text)
    return true
  }

  return { items, prefs, gameClients, clientInGame, setPrefs, setGameClients, watchGameClients, notify, notice, dismiss, clear, hold }
})
