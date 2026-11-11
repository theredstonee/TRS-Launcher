import { defineStore } from 'pinia'
import type { Settings, UiSettings } from '~/types'
import type { Locale } from '~/utils/i18n'

/** Design, Theme, Akzentfarbe, Unschärfe und Animationen sofort auf das Dokument anwenden. */
export function applyAppearance(ui: Pick<UiSettings, 'theme' | 'accent' | 'design' | 'advancedRendering' | 'motion' | 'startIntro' | 'startIntroSound' | 'startIntroFlicker' | 'startIntroVolume'> | undefined) {
  applyMotion(ui?.motion)
  if (typeof document === 'undefined') return
  // Start-Animation entscheidet beim nächsten Start sofort – ohne auf den Kern zu warten.
  mirrorIntroPrefs(ui)
  // Ohne Einstellungen (Fehler beim Laden) den Merker vom letzten Lauf nicht löschen.
  if (ui) applyDesign(ui.design)
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

const isPlain = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

/** Nur was sich zwischen `before` und `after` geändert hat, auf `target` übertragen. */
export function mergeChanges<T>(target: T, before: unknown, after: unknown): T {
  if (JSON.stringify(before) === JSON.stringify(after)) return target
  if (!isPlain(target) || !isPlain(before) || !isPlain(after)) return after as T
  const out: Record<string, unknown> = { ...target }
  for (const k of new Set([...Object.keys(before), ...Object.keys(after)])) {
    if (!(k in after)) delete out[k]
    else if (JSON.stringify(before[k]) !== JSON.stringify(after[k])) out[k] = mergeChanges(out[k], before[k], after[k])
  }
  return out as T
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

  // Speichern nacheinander, jede Änderung auf dem dann aktuellen Stand – sonst überschreibt
  // ein älterer Schnappschuss, was inzwischen woanders gespeichert wurde.
  let queue: Promise<unknown> = Promise.resolve()
  function update(change: (s: Settings) => Settings | null) {
    const run = queue.then(async () => {
      const base = current.value ?? (await load())
      const next = change(base)
      return next ? save(next) : base
    })
    queue = run.catch(() => {})
    return run
  }

  // „System“ folgt dem Windows-Farbmodus auch während der Laufzeit.
  if (typeof matchMedia === 'function') {
    matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => applyAppearance(current.value?.ui))
  }

  /** Sprache sofort umschalten und speichern (lädt die Einstellungen bei Bedarf). */
  async function setLanguage(language: Locale) {
    await setLocale(language)
    await update((base) => (base.ui.language === language ? null : { ...base, ui: { ...base.ui, language } }))
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

  /** Zählt Änderungen, die der Kern selbst gemacht hat (z. B. Clips im Spiel eingeschaltet). */
  const externalRevision = ref(0)

  /** Der Kern hat Einstellungen geändert: neu laden, ein offenes Einstellungsfenster gleicht an. */
  async function reloadExternal() {
    await load()
    externalRevision.value++
  }

  return { current, dialog, load, save, update, setLanguage, open, syncRevision, reloadFromSync, externalRevision, reloadExternal }
})
