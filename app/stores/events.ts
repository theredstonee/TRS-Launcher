import { defineStore } from 'pinia'
import { applyEventTheme, EVENT_STORAGE_KEY, HALLOWEEN_EVENT } from '~/utils/halloween'

/**
 * Events (API §31): welche für diesen Spieler gerade laufen (`me.events`, live über
 * `events_changed`). Ohne Event sieht alles aus wie immer. Das Theme liegt nur auf
 * `<html data-event>` – die gespeicherte Akzentfarbe bleibt. Zum Ausprobieren im
 * Browser (nur `nuxt dev`): `?event=halloween` an die Adresse hängen.
 */
export const useEventsStore = defineStore('events', () => {
  const trs = useTrsStore()
  /** Nur Entwicklung: Events von Hand setzen (`?event=halloween`). */
  const override = ref<string[] | null>(null)
  /** Zwischenspeicher des Kerns, bis `me` da ist (Splash beim nächsten Start). */
  const cached = ref<string[] | null>(null)
  if (import.meta.dev && typeof location !== 'undefined') {
    const q = new URLSearchParams(location.search).get('event')
    if (q) override.value = q.split(',').filter((id) => /^[a-z][a-z0-9_-]{0,31}$/.test(id))
  }
  // Letzten Lauf sofort übernehmen, sonst flackert das Theme und der Splash-Merker
  // geht verloren, bevor `me` oder der Kern-Cache antworten.
  if (import.meta.client) {
    try {
      if (localStorage.getItem(EVENT_STORAGE_KEY) === HALLOWEEN_EVENT) cached.value = [HALLOWEEN_EVENT]
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
  const halloween = computed(() => active.value.includes('halloween'))
  const has = (id: string) => active.value.includes(id)

  watch(active, (list) => applyEventTheme(list), { immediate: true })

  if (import.meta.client) {
    void backend.trs.activeEvents().then((list) => {
      const clean = list.filter((id) => /^[a-z][a-z0-9_-]{0,31}$/.test(id)).slice(0, 16)
      // Ein leerer Kern-Cache vor `me` ist noch keine Antwort – den Merker nicht löschen.
      if (clean.length > 0 || trs.me) cached.value = clean
    }).catch(() => {})
  }

  return { active, halloween, has, override }
})
