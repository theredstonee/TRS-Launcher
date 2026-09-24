import { isTauri } from '@tauri-apps/api/core'
import { error as logError, info as logInfo } from '@tauri-apps/plugin-log'
import { check, type Update } from '@tauri-apps/plugin-updater'
import type { PlatformCapabilities } from '~/types'

/** Alle paar Stunden erneut nachsehen – der Launcher läuft oft lange. */
const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000
/** Nach einem fehlgeschlagenen Download später still erneut versuchen. */
const RETRY_MS = 15 * 60 * 1000

/** `external`: Neue Version da, installiert wird sie über die Paketverwaltung (Linux .deb/.rpm/AUR). */
export type UpdatePhase = 'idle' | 'downloading' | 'ready' | 'installing' | 'failed' | 'external'

/**
 * Stille Updates: Die neue Version lädt im Hintergrund,
 * danach zeigt die Titelleiste nur „Neu starten zum Aktualisieren“. Ein Klick
 * installiert ohne Installer-Fenster und startet den Launcher neu.
 */
export const useUpdaterStore = defineStore('updater', () => {
  const phase = ref<UpdatePhase>('idle')
  const version = ref<string | null>(null)
  const percent = ref(0)
  const failReason = ref('')
  const failures = ref(0)

  let update: Update | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let started = false
  /** Wie sich der Launcher aktualisiert (Windows/AppImage: selbst, sonst die Paketverwaltung). */
  const mode = ref<PlatformCapabilities['updates']>(defaultCapabilities().updates)

  function schedule(ms: number) {
    clearTimeout(timer)
    timer = setTimeout(run, ms)
  }

  async function run() {
    if (phase.value !== 'idle' && phase.value !== 'failed') return
    try {
      const found = await check()
      if (!found) {
        schedule(CHECK_INTERVAL_MS)
        return
      }
      update = found
      version.value = found.version
      // .deb/.rpm/AUR: nur Bescheid geben – ersetzen darf die Paketverwaltung.
      if (mode.value === 'package') {
        phase.value = 'external'
        return
      }
      await download(found)
    } catch {
      // Offline oder Update-Kanal nicht erreichbar – später still erneut.
      schedule(CHECK_INTERVAL_MS)
    }
  }

  async function download(found: Update) {
    phase.value = 'downloading'
    percent.value = 0
    let total = 0
    let done = 0
    try {
      await found.download((event) => {
        if (event.event === 'Started') total = event.data.contentLength ?? 0
        if (event.event === 'Progress') {
          done += event.data.chunkLength
          if (total > 0) percent.value = Math.min(100, Math.floor((done / total) * 100))
        }
      })
      phase.value = 'ready'
      failures.value = 0
      logInfo(`Update auf ${found.version} geladen – bereit zum Neustart`).catch(() => {})
    } catch (e) {
      fail(e)
      schedule(RETRY_MS)
    }
  }

  function fail(e: unknown) {
    failReason.value = errorMessage(e)
    failures.value++
    phase.value = 'failed'
    logError(`Update auf ${version.value ?? '?'} fehlgeschlagen: ${failReason.value}`).catch(() => {})
  }

  /** Installiert die geladene Version still und startet neu (der Launcher beendet sich dabei). */
  async function restart() {
    if (!update || phase.value === 'external') return
    if (phase.value === 'failed') {
      await download(update)
      // download() setzt die Phase selbst neu.
      if ((phase.value as UpdatePhase) !== 'ready') return
    }
    phase.value = 'installing'
    // Den Ladebildschirm erst zeigen, dann installieren (der Launcher beendet sich dabei).
    await new Promise((resolve) => setTimeout(resolve, 400))
    try {
      await update.install()
    } catch (e) {
      fail(e)
    }
  }

  function start() {
    if (started || !isTauri() || import.meta.dev) return
    started = true
    loadAppInfo()
      .then((info) => (mode.value = info.capabilities.updates))
      .catch(() => {})
      .finally(() => {
        // Flatpak: Updates kommen über Flathub bzw. die Softwareverwaltung – hier nichts prüfen.
        if (mode.value === 'flatpak') return
        // Kurz warten, damit der Start des Launchers nicht mit dem Download konkurriert.
        schedule(5000)
      })
  }

  return { phase, version, percent, failReason, failures, mode, start, restart }
})
