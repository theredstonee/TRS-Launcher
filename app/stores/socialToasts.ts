import { defineStore } from 'pinia'
import { ref } from 'vue'
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
// `decide` (Einstellungen, Nicht stören, Vollbild, stummgeschaltet …).
export const useSocialToasts = defineStore('socialToasts', () => {
  const items = ref<SocialToast[]>([])
  const prefs = ref<SocialPrefs>({ ...defaultSocialPrefs })
  const timers = new Map<number, ReturnType<typeof setTimeout>>()
  /** Pausiert (Maus darüber / Schnellantwort offen). */
  const held = new Set<number>()

  function setPrefs(next: Partial<SocialPrefs> | null | undefined) {
    prefs.value = { ...defaultSocialPrefs, ...(next ?? {}) }
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
   * gerade, `muted` = Unterhaltung stummgeschaltet.
   */
  async function notify(
    kind: SocialToastKind,
    toast: Omit<SocialToast, 'id' | 'count' | 'kind'>,
    context: { looking?: boolean; muted?: boolean } = {},
  ): Promise<boolean> {
    const doc = typeof document === 'undefined' ? null : document
    const delivery = decide(kind, prefs.value, {
      focused: doc ? doc.hasFocus() : true,
      visible: doc ? doc.visibilityState === 'visible' : true,
      fullscreen: await fullscreen(),
      looking: context.looking ?? false,
      muted: context.muted ?? false,
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

  return { items, prefs, setPrefs, notify, dismiss, clear, hold }
})
