import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { defineStore } from 'pinia'
import type { ControllerType } from '~/utils/bigPicture'

export type BigLayer = { kind: 'menu' } | { kind: 'instance'; id: string } | { kind: 'target' }

/**
 * Big-Picture-Modus: eigene Seite (`/big`) im Vollbild für Fernseher, Steam Deck
 * und Controller. Merkt sich, ob das Fenster vorher schon Vollbild war, und stellt
 * es beim Verlassen wieder her. Nach dem Spielen holt es das Fenster zurück.
 */
export const useBigPictureStore = defineStore('bigPicture', () => {
  const router = useRouter()
  /** Die Big-Picture-Seite ist offen. */
  const active = ref(false)
  /** Wohin „Big Picture verlassen“ zurückführt. */
  const returnTo = ref('/')
  /** Zuletzt benutzte Eingabe – bestimmt die Knopf-Hinweise unten. */
  const controller = ref<ControllerType>('keyboard')
  /** Offene Ebene über dem Bereich: Menü (Start), Instanz-Optionen (Y), Instanz für „Beitreten“. */
  const layer = ref<BigLayer | null>(null)
  /** Instanz, mit der Server beitreten (`null` = zuletzt gespielte). */
  const joinTarget = ref<string | null>(null)
  let opener: HTMLElement | null = null
  let wasFullscreen: boolean | null = null
  let autoChecked = false

  const win = () => (isTauri() ? getCurrentWindow() : null)

  function open() {
    const current = router.currentRoute.value
    if (current.path === BIG_PICTURE_PATH) return
    returnTo.value = current.fullPath
    void router.push(BIG_PICTURE_PATH)
  }

  function close() {
    const target = returnTo.value && returnTo.value !== BIG_PICTURE_PATH ? returnTo.value : '/'
    void router.push(target)
  }

  function toggle() {
    if (router.currentRoute.value.path === BIG_PICTURE_PATH) close()
    else open()
  }

  /** Ebene öffnen; beim Schließen bekommt das auslösende Element den Fokus zurück. */
  function openLayer(next: BigLayer) {
    if (!layer.value) opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    layer.value = next
  }

  function closeLayer() {
    layer.value = null
    const back = opener
    opener = null
    void nextTick(() => {
      if (back?.isConnected) back.focus({ preventScroll: true })
    })
  }

  /** Seite ist da: Fenster in den Vollbildmodus (vorherigen Zustand merken). */
  async function activate() {
    active.value = true
    layer.value = null
    const w = win()
    if (!w) return
    if (wasFullscreen === null) wasFullscreen = await w.isFullscreen().catch(() => false)
    await w.setFullscreen(true).catch(() => {})
  }

  /** Seite verlassen: Fenster wie vorher (maximiert/normal stellt Windows selbst wieder her). */
  async function deactivate() {
    active.value = false
    layer.value = null
    opener = null
    const w = win()
    const restore = wasFullscreen === false
    wasFullscreen = null
    if (w && restore) await w.setFullscreen(false).catch(() => {})
  }

  /** Spiel beendet: minimiertes Fenster zurückholen, wieder Vollbild und Fokus. */
  async function bringBack() {
    const w = win()
    if (!w || !active.value) return
    if (await w.isMinimized().catch(() => false)) await w.unminimize().catch(() => {})
    await w.setFullscreen(true).catch(() => {})
    await w.setFocus().catch(() => {})
  }

  /**
   * Einmal beim App-Start: auf Wunsch immer, sonst auf Steam Deck/SteamOS/gamescope
   * (abschaltbar) gleich im Big-Picture-Modus öffnen.
   */
  async function autoStart() {
    if (autoChecked) return
    autoChecked = true
    const settings = useSettingsStore()
    const current = settings.current ?? (await settings.load().catch(() => null))
    const consoleSession = await loadAppInfo()
      .then((info) => info.capabilities.consoleSession === true)
      .catch(() => false)
    if (shouldOpenOnStart(current?.ui, consoleSession)) open()
  }

  return {
    active,
    returnTo,
    controller,
    layer,
    joinTarget,
    open,
    close,
    toggle,
    openLayer,
    closeLayer,
    activate,
    deactivate,
    bringBack,
    autoStart,
  }
})
