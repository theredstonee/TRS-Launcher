import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import type { ClipEvent, ClipOpenRequest, ClipState } from '~/types'

/** Aufnahmestand je laufendem Spiel, mit Empfangszeit (die Aufnahmezeit zählt die Oberfläche selbst weiter). */
export interface LiveClipState extends ClipState {
  receivedAt: number
}

/**
 * Clips & Aufnahme: Status der laufenden Spiele (Ereignis `clip-event` aus dem
 * Kern), Benachrichtigung bei gespeicherten Clips, Zähler für die Clip-Seite.
 */
export const useClipsStore = defineStore('clips', () => {
  const states = ref<Record<string, LiveClipState>>({})
  /** Steigt, wenn sich die Bibliothek geändert hat (Seite lädt neu). */
  const version = ref(0)
  const ffmpegDownloading = ref(false)
  let initialized = false

  const active = computed(() => Object.values(states.value))
  const recording = computed(() => active.value.some((s) => s.recording))

  function setState(state: ClipState) {
    states.value = { ...states.value, [state.instanceId]: { ...state, receivedAt: Date.now() } }
  }

  /** Player mit diesem Clip öffnen (Clip-Seite, Deep-Link). */
  function openClip(instanceId: string, fileName: string) {
    void navigateTo({ path: '/clips', query: { instance: instanceId, file: fileName, n: String(Date.now()) } })
  }

  function instanceName(id: string): string {
    return useInstancesStore().items.find((i) => i.id === id)?.name ?? id
  }

  function onEvent(event: ClipEvent) {
    const toasts = useToasts()
    switch (event.type) {
      case 'state':
        setState(event)
        break
      case 'ended': {
        const next = { ...states.value }
        delete next[event.instanceId]
        states.value = next
        break
      }
      case 'saved': {
        version.value++
        const text = t(event.kind === 'recording' ? 'clips.toasts.recordingSaved' : 'clips.toasts.clipSaved', {
          duration: formatClipDuration(event.seconds * 1000),
          instance: instanceName(event.instanceId),
        })
        toasts.ok(text, { label: t('clips.toasts.show'), run: () => openClip(event.instanceId, event.fileName) })
        if (event.removed > 0) toasts.info(t('clips.toasts.limitCleanup', event.removed))
        break
      }
      case 'failed':
        toasts.error(failureText(event.code))
        break
      case 'ffmpeg':
        ffmpegDownloading.value = event.state === 'downloading'
        if (event.state === 'failed') toasts.error(t('clips.errors.ffmpegDownload'))
        break
      case 'enabled':
        // Im Spiel eingeschaltet: Einstellungen (Schalter) sofort nachziehen.
        void useSettingsStore().reloadExternal().catch(() => {})
        toasts.info(t('clips.toasts.enabledInGame', { instance: instanceName(event.instanceId) }))
        break
    }
  }

  async function init() {
    if (initialized || !isTauri()) return
    initialized = true
    await listen<ClipEvent>('clip-event', (e) => onEvent(e.payload))
    // „Im Launcher öffnen“ aus dem Spiel: der Kern hat das Fenster schon nach vorn geholt.
    await listen<ClipOpenRequest>('clip-open', (e) => openClip(e.payload.instanceId, e.payload.fileName))
    try {
      for (const s of await backend.clipStates()) setState(s)
    } catch {
      // Kein Rekorder aktiv – egal.
    }
  }

  async function action(instanceId: string, record: boolean) {
    try {
      await backend.clipAction(instanceId, record)
    } catch (e) {
      useToasts().error(e)
    }
  }

  return { states, active, recording, version, ffmpegDownloading, init, action, onEvent, openClip }
})
