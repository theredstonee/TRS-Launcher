import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (TRS- und Einstellungs-Store
// kommen in der App über Nuxts Auto-Imports, im Test als Attrappen).
import { backend } from '../utils/backend'
import { applyEventTheme, EVENT_STORAGE_KEY, HALLOWEEN_EVENT } from '../utils/halloween'

const EVENT_ID = /^[a-z][a-z0-9_-]{0,31}$/

function cleanList(list: readonly string[]): string[] {
  return list.filter((id) => EVENT_ID.test(id)).slice(0, 16)
}

/**
 * Events (API §31): welche für diesen Spieler gerade laufen (`me.events`, live über
 * `events_changed`). Ohne Event sieht alles aus wie immer. Zum Ausprobieren im
 * Browser (nur `nuxt dev`): `?event=halloween` an die Adresse hängen.
 *
 * Zwei Signale:
 * - `active` / `halloween` = FEATURES (Kürbis-Suche, Süßes oder Saures, Sticker, Kosmetik claimen …).
 * - `look` / `lookHalloween` = LOOK & Effekte (Theme auf `<html data-event>`, Szenen, Ladebalken,
 *   Atmosphäre, Event-App-Symbol, Splash/Start-Animation über den Merker). `look` = aktive Events
 *   ∩ Server-Look (`me.eventLook`, ohne Team-Sperre) ∩ eigener Schalter `ui.eventLook`.
 * Die gespeicherte Akzentfarbe bleibt immer unangetastet.
 */
export const useEventsStore = defineStore('events', () => {
  const trs = useTrsStore()
  const settings = useSettingsStore()
  /** Nur Entwicklung: Events von Hand setzen (`?event=halloween`). */
  const override = ref<string[] | null>(null)
  /** Zwischenspeicher des Kerns, bis `me` da ist (Splash beim nächsten Start). */
  const cached = ref<string[] | null>(null)
  /** Look-Zwischenspeicher des Kerns (Team-Sperre abgezogen), bis `me` da ist. */
  const cachedLook = ref<string[] | null>(null)
  /** Merker vom letzten Lauf: Lief der Look? (Gilt, bis die Einstellungen geladen sind.) */
  const storedLook = ref<string[]>([])
  if (import.meta.dev && typeof location !== 'undefined') {
    const q = new URLSearchParams(location.search).get('event')
    if (q) override.value = q.split(',').filter((id) => EVENT_ID.test(id))
  }
  // Letzten Lauf sofort übernehmen, sonst flackert das Theme und der Splash-Merker
  // geht verloren, bevor `me` oder der Kern-Cache antworten.
  if (import.meta.client) {
    try {
      if (localStorage.getItem(EVENT_STORAGE_KEY) === HALLOWEEN_EVENT) {
        cached.value = [HALLOWEEN_EVENT]
        storedLook.value = [HALLOWEEN_EVENT]
      }
    } catch {
      // Privater Modus: ohne Merker bleibt der nächste Splash beim normalen Bild.
    }
  }

  const active = computed<string[]>(() => {
    if (override.value) return override.value
    if (trs.me) return trs.me.events ?? []
    // Abgelehnt oder nicht angemeldet: kein Event. Solange der Status fehlt, letzten Lauf behalten.
    if (trs.status && !trs.enabled) return []
    return cached.value ?? []
  })
  /** Server-Look: aktive Events ohne Team-Sperre. Fehlt `eventLook` (älterer Kern/Server) ⇒ = Events. */
  const serverLook = computed<string[]>(() => {
    if (override.value) return override.value
    if (trs.me) return trs.me.eventLook ?? trs.me.events ?? []
    if (trs.status && !trs.enabled) return []
    return cachedLook.value ?? cached.value ?? []
  })
  const look = computed<string[]>(() => {
    const ui = settings.current?.ui
    // Einstellungen noch nicht geladen: beim Look vom letzten Lauf bleiben (kein Aufblitzen).
    if (!ui) return override.value ?? storedLook.value
    if (ui.eventLook === false) return []
    return active.value.filter((id) => serverLook.value.includes(id))
  })
  const halloween = computed(() => active.value.includes(HALLOWEEN_EVENT))
  const lookHalloween = computed(() => look.value.includes(HALLOWEEN_EVENT))
  const has = (id: string) => active.value.includes(id)

  watch(look, (list) => applyEventTheme(list), { immediate: true })

  // Event-Symbol (Fenster, Verknüpfungen, Startsymbol am Handy): beim Start, bei `events_changed`
  // und wenn „Event-Symbole“ oder „Event-Design anzeigen“ umgelegt wird. Erst mit geladenen
  // Einstellungen (der Kern liest die Schalter selbst); gleiche Lage → kein erneuter Aufruf.
  const iconKey = computed(() => {
    const ui = settings.current?.ui
    if (!ui) return null
    return `${ui.eventIcons === false ? 'off' : 'on'}|${look.value.join(',')}`
  })
  watch(
    iconKey,
    (key) => {
      if (key === null) return
      backend.eventIconSync(look.value.slice(0, 16)).catch(() => {
        // Ohne Tauri (Browser) oder Fehler im Kern: das normale Symbol bleibt.
      })
    },
    { immediate: true },
  )

  if (import.meta.client) {
    // Älterer Kern ohne `trs_look_events`: Look = Events.
    const lookList = Promise.resolve()
      .then(() => backend.trs.lookEvents())
      .catch(() => null)
    void backend.trs.activeEvents().then(async (list) => {
      const clean = cleanList(list)
      const lookClean = await lookList
      // Ein leerer Kern-Cache vor `me` ist noch keine Antwort – den Merker nicht löschen.
      if (clean.length > 0 || trs.me) {
        cached.value = clean
        cachedLook.value = lookClean ? cleanList(lookClean) : null
      }
    }).catch(() => {})
  }

  return { active, halloween, has, look, lookHalloween, override }
})
