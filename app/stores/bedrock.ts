import { defineStore } from 'pinia'

// Minecraft Bedrock (Microsoft Store / Xbox-App, nur Windows): wird nur erkannt und gestartet –
// keine Instanz, keine Mods, kein Konto des Launchers. Ohne Installation (oder unter Linux)
// bleibt die Karte in der Bibliothek weg.
export const useBedrockStore = defineStore('bedrock', () => {
  const installed = ref(false)
  const starting = ref(false)
  const toasts = useToasts()
  const settings = useSettingsStore()

  /** Karte zeigen: installiert und in den Einstellungen nicht ausgeblendet. */
  const visible = computed(() => installed.value && settings.current?.showBedrock !== false)

  async function load() {
    try {
      installed.value = (await backend.bedrockInfo()).installed === true
    } catch {
      installed.value = false
    }
  }

  async function launch() {
    if (starting.value) return
    starting.value = true
    try {
      await backend.launchBedrock()
      toasts.ok(t('bedrock.started'))
    } catch (e) {
      toasts.error(e)
    } finally {
      // Der Store-Start dauert einen Moment – den Knopf kurz sperren, damit niemand doppelt klickt.
      setTimeout(() => (starting.value = false), 4000)
    }
  }

  return { installed, starting, visible, load, launch }
})
