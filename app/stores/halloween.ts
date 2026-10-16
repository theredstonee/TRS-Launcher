import { defineStore } from 'pinia'
import { computed, reactive, ref, watch } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (`useTrsStore`,
// `useEventsStore` und `useToasts` bleiben Auto-Imports, im Test Attrappen).
import { backend, errorMessage } from '../utils/backend'
import {
  canTrick,
  halloweenFeaturesOn,
  HALLOWEEN_END,
  PUMPKIN_TOTAL,
  showPumpkin,
  type HalloweenState,
  type HalloweenUpdatedEvent,
  type LauncherPumpkin,
  type Sticker,
  type TrickOutcome,
} from '../utils/halloween'
import { t } from '../utils/i18n'

/** Nur `nuxt dev`: `?hwdemo` spielt das Event ohne API durch (Kürbisse, Anklopfen, Sticker). */
function demoState(): HalloweenState {
  return {
    active: true,
    endsAt: HALLOWEEN_END,
    pumpkins: {
      total: PUMPKIN_TOTAL,
      found: ['web-home', 'web-features', 'client-title'],
      nextHint: { de: 'Wo die Fenster schließen, webt die Spinne ihr Netz.', en: 'Where windows close, the spider weaves its web.', es: 'Donde se cierran las ventanas, la araña teje su red.' },
    },
    trick: { usedToday: false, result: null, reward: null, next: null },
    badges: ['halloween2026'],
    effects: { hits: true, trail: true },
    wings: { choice: null, canChoose: true, options: ['bat_wings', 'demon_wings', 'raven_wings', 'ghost_wings'] },
  }
}

/**
 * Halloween 2026 (API §36): Stand der Kürbis-Suche, Süßes oder Saures, Spuk-Effekte,
 * Sticker und Abzeichen-Bilder. Lädt nur, solange `events.halloween` läuft. Fehlt der
 * Stand (alte API, offline), bleibt `state` leer und die Oberfläche zeigt nur den Look.
 */
export const useHalloweenStore = defineStore('halloween', () => {
  const state = ref<HalloweenState | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const stickers = ref<Sticker[] | null>(null)
  /** Abzeichen-Bilder (Data-URL) je ID; `null` = gibt es nicht. */
  const badgeImages = reactive<Record<string, string | null>>({})
  const demo = import.meta.dev && typeof location !== 'undefined' && new URLSearchParams(location.search).has('hwdemo')
  /** Uhr für „vor dem Ende“ und „morgen wieder“ (minütlich). */
  const now = ref(Date.now())
  if (typeof setInterval === 'function') setInterval(() => (now.value = Date.now()), 60_000)

  const on = computed(() => halloweenFeaturesOn(state.value, now.value))
  const found = computed(() => state.value?.pumpkins.found.length ?? 0)
  const total = computed(() => state.value?.pumpkins.total ?? PUMPKIN_TOTAL)
  const trickOpen = computed(() => canTrick(state.value, now.value))
  const visible = (id: LauncherPumpkin) => showPumpkin(state.value, id, now.value)

  async function load() {
    const events = useEventsStore()
    if (!events.halloween) {
      state.value = null
      return
    }
    if (demo) {
      state.value ??= demoState()
      return
    }
    const trs = useTrsStore()
    if (!trs.enabled || !trs.status?.account) {
      state.value = null
      return
    }
    loading.value = true
    error.value = null
    try {
      state.value = await backend.halloween.state()
    } catch (e) {
      // Ohne Stand nur der Look – kein Fehler-Toast, die Seite bleibt ruhig.
      state.value = null
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }

  const finding = new Set<string>()
  /** Kürbis gefunden: melden, Stand übernehmen, Toast „Kürbis gefunden! n/13“. */
  async function find(id: LauncherPumpkin): Promise<boolean> {
    if (!visible(id) || finding.has(id)) return false
    finding.add(id)
    const toasts = useToasts()
    try {
      const r = demo
        ? (() => {
            const list = [...(state.value?.pumpkins.found ?? []), id]
            return { found: list, count: list.length, complete: list.length >= PUMPKIN_TOTAL, reward: null }
          })()
        : await backend.halloween.findPumpkin(id)
      if (state.value) {
        state.value = { ...state.value, pumpkins: { ...state.value.pumpkins, found: r.found.includes(id) ? r.found : [...r.found, id] } }
      }
      const count = Math.max(r.count, state.value?.pumpkins.found.length ?? 0)
      toasts.ok(r.complete ? t('halloween.hunt.complete') : t('halloween.hunt.found', { n: count, total: total.value }))
      // Nächster Hinweis kommt vom Server.
      if (!demo) void load()
      return true
    } catch (e) {
      toasts.error(e)
      return false
    } finally {
      finding.delete(id)
    }
  }

  /** Süßes oder Saures – der Server würfelt; danach ist heute zu. */
  async function trick(): Promise<TrickOutcome> {
    const outcome: TrickOutcome = demo
      ? Math.random() < 0.25
        ? { result: 'sour', reward: null, next: null }
        : { result: 'sweet', reward: { kind: 'sticker', id: 'ghost_boo', name: { de: 'Buh!', en: 'Boo!', es: '¡Bu!' } }, next: null }
      : await backend.halloween.trick()
    if (state.value) {
      state.value = { ...state.value, trick: { usedToday: true, result: outcome.result, reward: outcome.reward, next: outcome.next ?? state.value.trick.next } }
    }
    if (outcome.reward?.kind === 'sticker') stickers.value = null
    if (!demo) {
      // Kosmetik/Umhänge neu laden, damit der neue Umhang sofort auftaucht.
      useTrsStore().capesRevision++
    }
    return outcome
  }

  /** Flügel wählen (nach allen 13 Kürbissen, endgültig). */
  async function chooseWings(id: string) {
    if (demo) {
      if (state.value) state.value = { ...state.value, wings: { ...state.value.wings, choice: id, canChoose: false } }
      return
    }
    state.value = await backend.halloween.chooseWings(id)
    useTrsStore().capesRevision++
  }

  async function setEffects(effects: { hits: boolean; trail: boolean }) {
    const before = state.value?.effects
    if (state.value) state.value = { ...state.value, effects }
    try {
      if (!demo) await backend.halloween.setEffects(effects)
    } catch (e) {
      if (state.value && before) state.value = { ...state.value, effects: before }
      useToasts().error(e)
    }
  }

  async function loadStickers(force = false): Promise<Sticker[]> {
    if (stickers.value && !force) return stickers.value
    if (demo) {
      stickers.value = [{ id: 'ghost_boo', name: { de: 'Buh!', en: 'Boo!', es: '¡Bu!' }, image: null, owned: true }]
      return stickers.value
    }
    try {
      stickers.value = await backend.halloween.stickers()
    } catch {
      stickers.value = []
    }
    return stickers.value
  }

  const pendingBadges = new Set<string>()
  /** Bilder fehlender Abzeichen nachladen (gebündelt, jede ID nur einmal). */
  async function ensureBadges(ids: readonly string[]) {
    const missing = [...new Set(ids)].filter((id) => !(id in badgeImages) && !pendingBadges.has(id))
    if (!missing.length || demo) return
    for (const id of missing) pendingBadges.add(id)
    try {
      const list = await backend.halloween.badgeImages(missing)
      for (const id of missing) badgeImages[id] = list.find((b) => b.id === id)?.image ?? null
    } catch {
      // Offline: beim nächsten Mal erneut versuchen.
    } finally {
      for (const id of missing) pendingBadges.delete(id)
    }
  }

  /** Abzeichen anderer Spieler (aus dem Spieler-Lookup), je UUID. */
  const playerBadges = reactive<Record<string, string[]>>({})
  function rememberPlayers(list: readonly { uuid: string; badges?: string[] }[]) {
    const all: string[] = []
    for (const p of list) {
      playerBadges[p.uuid] = p.badges ?? []
      all.push(...(p.badges ?? []))
    }
    if (all.length) void ensureBadges(all)
  }
  const wanted = new Set<string>()
  const asked = new Set<string>()
  let wantTimer: ReturnType<typeof setTimeout> | undefined
  /** Abzeichen dieser Spieler nachladen (gebündelt, jede UUID einmal je Sitzung). */
  function wantPlayers(uuids: readonly string[]) {
    for (const u of uuids) if (/^[0-9a-f]{32}$/.test(u) && !asked.has(u) && !(u in playerBadges)) wanted.add(u)
    if (!wanted.size || wantTimer || demo) return
    wantTimer = setTimeout(async () => {
      wantTimer = undefined
      const batch = [...wanted].slice(0, 100)
      for (const u of batch) {
        wanted.delete(u)
        asked.add(u)
      }
      try {
        rememberPlayers(await backend.trs.playerCapes(batch))
      } catch {
        // Offline/ohne TRS: keine Abzeichen.
      }
      if (wanted.size) wantPlayers([])
    }, 60)
  }

  function onLiveEvent(e: HalloweenUpdatedEvent) {
    const { type: _type, ...rest } = e
    state.value = rest
  }

  // Event an/aus oder Konto gewechselt → neu laden.
  if (typeof window !== 'undefined') {
    const events = useEventsStore()
    const trs = useTrsStore()
    watch(
      () => [events.halloween, trs.enabled, trs.status?.account] as const,
      () => void load(),
      { immediate: true },
    )
  }

  return {
    state,
    loading,
    error,
    stickers,
    badgeImages,
    demo,
    on,
    found,
    total,
    trickOpen,
    visible,
    load,
    find,
    trick,
    chooseWings,
    setEffects,
    loadStickers,
    ensureBadges,
    playerBadges,
    rememberPlayers,
    wantPlayers,
    onLiveEvent,
  }
})
