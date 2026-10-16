// Halloween 2026 „Nacht der langen Schatten“ (API §36) auf der Website: Look an/aus (Event global an), Schalter
// „Flackern & Rauschen“ (localStorage, Standard an), Spielstand des angemeldeten Kontos, Kürbis-Funde, Anklopfen,
// Flügel-Wahl und eine kleine Meldung (Toast). Persönliches lädt erst im Browser.

import { HALLOWEEN_TEXTS } from '~/utils/halloween-i18n'

export interface HalloweenReward {
  kind: 'cape' | 'emote' | 'sticker'
  id: string
  name: { de: string, en: string, es: string }
}

export interface HalloweenMe {
  active: boolean
  endsAt: string
  pumpkins: { total: number, found: string[], nextHint: { de: string, en: string, es: string } | null }
  trick: { usedToday: boolean, result?: 'sweet' | 'sour', reward?: HalloweenReward, next: string }
  badges: string[]
  effects: { hits: boolean, trail: boolean }
  wings?: { choice: string | null, canChoose: boolean, options: string[] }
}

export interface HalloweenToast {
  title: string
  text: string
  link?: { to: string, label: string }
  at: number
}

const FLICKER_KEY = 'trs-halloween-flicker'
/** Laufendes Laden (mehrere Kürbisse + Karte auf einer Seite fragen nur einmal). */
let inflight: Promise<HalloweenMe | null> | null = null
const HINT_KEY = 'trs-halloween-flicker-hint'

function readStore(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}
function writeStore(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // privater Modus: Einstellung gilt dann nur bis zum Neuladen
  }
}

export function useHalloween() {
  const { lang } = useLang()
  const { data: events } = usePublicEvents()
  /** Look an: Event `halloween` global an (wie bisher die Klasse am html). */
  const on = computed(() => events.value?.events.some((e: { id: string, active: boolean }) => e.id === 'halloween' && e.active) ?? false)
  const t = computed(() => HALLOWEEN_TEXTS[lang.value] ?? HALLOWEEN_TEXTS.en)
  const flicker = useState<boolean>('hw-flicker', () => true)
  /** Erst-Hinweis schon gesehen? Vor dem Lesen aus localStorage „ja“, damit SSR nichts aufblitzen lässt. */
  const hintSeen = useState<boolean>('hw-hint-seen', () => true)
  const prefsLoaded = useState<boolean>('hw-prefs', () => false)
  const me = useState<HalloweenMe | null>('hw-me', () => null)
  const meLoaded = useState<boolean>('hw-me-loaded', () => false)
  const toast = useState<HalloweenToast | null>('hw-toast', () => null)
  const { account, load, api, loginUrl } = useAccount()

  function loadPrefs(): void {
    if (prefsLoaded.value || import.meta.server) return
    prefsLoaded.value = true
    flicker.value = readStore(FLICKER_KEY) !== '0'
    hintSeen.value = readStore(HINT_KEY) === '1'
    document.documentElement.classList.toggle('hw-noflick', !flicker.value)
  }

  function setFlicker(v: boolean): void {
    flicker.value = v
    writeStore(FLICKER_KEY, v ? '1' : '0')
    document.documentElement.classList.toggle('hw-noflick', !v)
  }

  function dismissHint(): void {
    hintSeen.value = true
    writeStore(HINT_KEY, '1')
  }

  function say(title: string, text: string, link?: HalloweenToast['link']): void {
    toast.value = { title, text, link, at: Date.now() }
  }

  /** Spielstand laden (nur angemeldet). */
  function refresh(): Promise<HalloweenMe | null> {
    inflight ??= doRefresh().finally(() => {
      inflight = null
    })
    return inflight
  }

  async function doRefresh(): Promise<HalloweenMe | null> {
    const acc = await load()
    if (!acc) {
      me.value = null
      meLoaded.value = true
      return null
    }
    try {
      me.value = await api<HalloweenMe>('/v1/me/halloween')
    } catch {
      me.value = null
    } finally {
      meLoaded.value = true
    }
    return me.value
  }

  function errorCode(e: unknown): string {
    const d = (e as { data?: { error?: { code?: string } } }).data
    return d?.error?.code ?? ''
  }

  /** Kürbis angeklickt. Ohne Anmeldung: Hinweis + Link zur Anmeldung. `true` = gezählt (oder schon gefunden). */
  async function findPumpkin(id: string): Promise<boolean> {
    const acc = await load()
    if (!acc) {
      say(t.value.found, t.value.loginToCount, { to: loginUrl(), label: t.value.loginToCountLink })
      return false
    }
    try {
      const r = await api<{ found: string[], count: number, complete: boolean }>(`/v1/me/halloween/pumpkins/${encodeURIComponent(id)}`, { method: 'POST' })
      if (me.value) me.value = { ...me.value, pumpkins: { ...me.value.pumpkins, found: r.found } }
      say(t.value.found, r.complete ? t.value.foundAll : t.value.foundOf.replace('{n}', String(r.count)))
      void refresh()
      return true
    } catch (e) {
      const code = errorCode(e)
      if (code === 'event_inactive') say(t.value.found, t.value.inactive)
      else if (code !== 'rate_limited') say(t.value.found, t.value.error)
      return false
    }
  }

  /** Anklopfen. Liefert das Ergebnis oder `null` (Fehler, schon geklopft → Stand neu geladen). */
  async function knock(): Promise<{ result: 'sweet' | 'sour', reward?: HalloweenReward, next: string } | null> {
    try {
      const r = await api<{ result: 'sweet' | 'sour', reward?: HalloweenReward, next: string }>('/v1/me/halloween/trick', { method: 'POST' })
      void refresh()
      return r
    } catch (e) {
      const code = errorCode(e)
      if (code === 'event_inactive') say(t.value.trickTitle, t.value.inactive)
      else if (code !== 'trick_used') say(t.value.trickTitle, t.value.error)
      void refresh()
      return null
    }
  }

  async function chooseWings(id: string): Promise<boolean> {
    try {
      await api('/v1/me/halloween/wings', { method: 'POST', body: { id } })
      await refresh()
      return true
    } catch {
      say(t.value.wingsTitle, t.value.error)
      await refresh()
      return false
    }
  }

  return { on, t, lang, flicker, hintSeen, loadPrefs, setFlicker, dismissHint, me, meLoaded, account, refresh, findPumpkin, knock, chooseWings, toast, say, loginUrl }
}

/** Bewegung reduzieren (System) – live. */
export function useReducedMotion() {
  const reduced = ref(false)
  onMounted(() => {
    const q = window.matchMedia('(prefers-reduced-motion: reduce)')
    reduced.value = q.matches
    const on = () => {
      reduced.value = q.matches
    }
    q.addEventListener('change', on)
    onBeforeUnmount(() => q.removeEventListener('change', on))
  })
  return reduced
}
