import { defineStore } from 'pinia'
import type { Settings, UiSettings } from '~/types'

/** Theme, Akzentfarbe und Unschärfe sofort auf das Dokument anwenden. */
export function applyAppearance(ui: Pick<UiSettings, 'theme' | 'accent' | 'advancedRendering'> | undefined) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  const theme = ui?.theme ?? 'dark'
  const prefersLight = typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: light)').matches
  const resolved = theme === 'system' ? (prefersLight ? 'light' : 'dark') : theme
  if (resolved === 'dark') delete root.dataset.theme
  else root.dataset.theme = resolved
  if (!ui || ui.accent === 'redstone') delete root.dataset.accent
  else root.dataset.accent = ui.accent
  root.classList.toggle('no-blur', ui?.advancedRendering === false)
}

export const useSettingsStore = defineStore('settings', () => {
  const current = ref<Settings | null>(null)
  /** Welcher Bereich des Einstellungs-Modals offen ist; `null` = zu. */
  const dialog = ref<string | null>(null)

  async function load() {
    current.value = await backend.getSettings()
    applyAppearance(current.value.ui)
    return current.value
  }

  async function save(settings: Settings) {
    current.value = await backend.updateSettings(settings)
    applyAppearance(current.value.ui)
    return current.value
  }

  // „System“ folgt dem Windows-Farbmodus auch während der Laufzeit.
  if (typeof matchMedia === 'function') {
    matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => applyAppearance(current.value?.ui))
  }

  function open(section = 'appearance') {
    dialog.value = section
  }

  return { current, dialog, load, save, open }
})
