import { defineStore } from 'pinia'
import type { Settings, UiSettings } from '~/types'
import type { Locale } from '~/utils/i18n'

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
    void setLocale(current.value.ui.language)
    return current.value
  }

  async function save(settings: Settings) {
    current.value = await backend.updateSettings(settings)
    applyAppearance(current.value.ui)
    void setLocale(current.value.ui.language)
    return current.value
  }

  // „System“ folgt dem Windows-Farbmodus auch während der Laufzeit.
  if (typeof matchMedia === 'function') {
    matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => applyAppearance(current.value?.ui))
  }

  /** Sprache sofort umschalten und speichern (lädt die Einstellungen bei Bedarf). */
  async function setLanguage(language: Locale) {
    await setLocale(language)
    const base = current.value ?? (await load())
    if (base.ui.language === language) return
    await save({ ...base, ui: { ...base.ui, language } })
  }

  function open(section = 'appearance') {
    dialog.value = section
  }

  /** Zählt Übernahmen vom TRS-Konto – ein offenes Einstellungsfenster gleicht Theme/Sprache an. */
  const syncRevision = ref(0)

  /** Theme, Akzentfarbe oder Sprache kamen vom TRS-Konto: neu laden und sofort anwenden. */
  async function reloadFromSync() {
    await load()
    syncRevision.value++
  }

  return { current, dialog, load, save, setLanguage, open, syncRevision, reloadFromSync }
})
