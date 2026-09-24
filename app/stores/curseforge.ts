import { defineStore } from 'pinia'

/**
 * CurseForge: ob dieser Build die Quelle anbietet (API-Schlüssel eingebaut)
 * und der Dialog für Dateien, die der Nutzer selbst laden muss – global im
 * Layout, damit ihn Entdecken-Seite, Projektseite und Inhaltsliste öffnen können.
 */
export const useCurseForgeStore = defineStore('curseforge', () => {
  /** `null` = noch nicht gefragt. */
  const available = ref<boolean | null>(null)
  /** Instanz, deren „von Hand laden“-Liste gerade offen ist. */
  const blockedFor = ref<string | null>(null)
  /** Zähler je Instanz (für Hinweise in der Inhaltsliste); erhöht sich bei jeder Änderung. */
  const revision = ref(0)
  let loading: Promise<boolean> | null = null

  function load(): Promise<boolean> {
    if (available.value !== null) return Promise.resolve(available.value)
    loading ??= backend.curseforge
      .status()
      .then((s) => s.available)
      .catch(() => false)
      .then((ok) => (available.value = ok))
    return loading
  }

  function openBlocked(instanceId: string) {
    blockedFor.value = instanceId
  }

  function closeBlocked() {
    blockedFor.value = null
    revision.value++
  }

  /** Liste hat sich geändert (installiert, übernommen, verworfen). */
  function touch() {
    revision.value++
  }

  return { available, blockedFor, revision, load, openBlocked, closeBlocked, touch }
})
