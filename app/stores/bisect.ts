import { isTauri } from '@tauri-apps/api/core'
import { defineStore } from 'pinia'
import type { BisectView } from '~/utils/bisect'

/**
 * „Schuldige Mod finden“: Stand der Suchen je Instanz. Der Kern schaltet die
 * Mods um, merkt sich den Ursprung auf der Platte und stellt ihn am Ende wieder
 * her. Hier: Fenster-Zustand, Antworten und das automatische Werten eines
 * Absturzes (aus dem Games-Store).
 */
export const useBisectStore = defineStore('bisect', () => {
  const views = ref<Record<string, BisectView>>({})
  const busy = ref<Set<string>>(new Set())
  /** Instanz, für die gerade der Start-Dialog offen ist. */
  const confirming = ref<string | null>(null)
  /** Steigt bei jeder Änderung – der Inhalte-Tab lädt dann neu. */
  const revision = ref(0)
  const minimized = ref(false)
  /** Antwort, die auf das Spielende wartet (Spiel wird dafür beendet). */
  const pending = new Map<string, boolean>()
  let initialized = false

  function set(view: BisectView) {
    views.value = { ...views.value, [view.instanceId]: view }
    revision.value++
  }

  function drop(id: string) {
    const next = { ...views.value }
    delete next[id]
    views.value = next
    pending.delete(id)
    revision.value++
  }

  const view = (id: string): BisectView | null => views.value[id] ?? null
  const isActive = (id: string) => !!views.value[id]
  const isBusy = (id: string) => busy.value.has(id)
  /** Die Suche, die das Fenster zeigt (meist gibt es nur eine). */
  const current = computed(() => Object.values(views.value).sort((a, b) => a.startedAt.localeCompare(b.startedAt))[0] ?? null)

  /** Beim App-Start: Suchen aus einer früheren Sitzung (z. B. nach einem Absturz) wieder zeigen. */
  async function init() {
    if (initialized || !isTauri()) return
    initialized = true
    try {
      for (const v of await backend.bisectActive()) set(v)
    } catch {
      // Ohne Antwort gibt es nichts fortzusetzen.
    }
  }

  async function run(id: string, work: () => Promise<BisectView | void>): Promise<boolean> {
    if (busy.value.has(id)) return false
    busy.value = new Set(busy.value).add(id)
    try {
      const result = await work()
      if (result) set(result)
      return true
    } catch (e) {
      useToasts().error(e)
      return false
    } finally {
      const next = new Set(busy.value)
      next.delete(id)
      busy.value = next
    }
  }

  function askStart(id: string) {
    if (isActive(id)) {
      minimized.value = false
      return
    }
    confirming.value = id
  }

  async function start(id: string) {
    confirming.value = null
    minimized.value = false
    await run(id, () => backend.bisectStart(id))
  }

  /** `failed`: Der Fehler tritt noch auf. Läuft das Spiel, wird es erst beendet. */
  async function answer(id: string, failed: boolean) {
    const v = views.value[id]
    if (!v || v.phase !== 'testing') return
    const games = useGamesStore()
    if (games.state(id).phase === 'running') {
      pending.set(id, failed)
      await games.stop(id)
      return
    }
    if (games.state(id).phase !== 'idle') return
    await run(id, () => backend.bisectAnswer(id, v.round, failed))
  }

  /**
   * Spielende (Games-Store): wartende Antwort werten, sonst einen Absturz als
   * „Fehler noch da“. `true` = die Suche kümmert sich, kein Absturz-Hinweis nötig.
   */
  function gameExited(id: string, crashed: boolean): boolean {
    const v = views.value[id]
    if (!v || v.phase !== 'testing') {
      pending.delete(id)
      return false
    }
    if (pending.has(id)) {
      const failed = pending.get(id)!
      pending.delete(id)
      void answer(id, failed)
      return true
    }
    if (crashed) {
      useToasts().info(t('bisect.crashDetected'))
      void answer(id, true)
      return true
    }
    minimized.value = false
    return false
  }

  async function keepSearching(id: string) {
    await run(id, () => backend.bisectContinue(id))
  }

  /** Beenden oder Abbrechen – der Kern stellt den Ursprung wieder her. */
  async function finish(id: string, disableResult = false) {
    const ok = await run(id, async () => {
      await backend.bisectFinish(id, disableResult)
    })
    if (!ok) return
    drop(id)
    useToasts().ok(disableResult ? t('bisect.toasts.disabled') : t('bisect.toasts.restored'))
  }

  return {
    views,
    busy,
    confirming,
    revision,
    minimized,
    current,
    view,
    isActive,
    isBusy,
    init,
    askStart,
    start,
    answer,
    gameExited,
    keepSearching,
    finish,
  }
})
