import { defineStore } from 'pinia'
import type { Instance, NewInstance, UnavailableInstance } from '~/types'

export const useInstancesStore = defineStore('instances', () => {
  const items = ref<Instance[]>([])
  const loading = ref(false)
  /** Mindestens einmal erfolgreich geladen – vorher ist eine leere Liste nicht aussagekräftig. */
  const loaded = ref(false)
  const error = ref<string | null>(null)
  /** Instanzen an eigenem Ort, deren Ordner gerade fehlt (Laufwerk getrennt). */
  const unavailable = ref<UnavailableInstance[]>([])

  // Überholt ein neueres load() ein älteres, gewinnt das neuere.
  let loadSeq = 0
  async function load() {
    const seq = ++loadSeq
    loading.value = true
    error.value = null
    try {
      const list = await backend.listInstances()
      if (seq !== loadSeq) return
      items.value = list
      loaded.value = true
    } catch (e) {
      if (seq !== loadSeq) return
      error.value = errorMessage(e)
    } finally {
      if (seq === loadSeq) loading.value = false
    }
    backend
      .unavailableInstances()
      .catch(() => [])
      .then((list) => {
        if (seq === loadSeq) unavailable.value = list
      })
  }

  /** Nicht erreichbare Instanz aus der Liste nehmen (ihr Ordner bleibt unberührt). */
  async function forget(id: string) {
    await backend.forgetUnavailableInstance(id)
    unavailable.value = unavailable.value.filter((u) => u.id !== id)
  }

  async function create(instance: NewInstance) {
    const created = await backend.createInstance(instance)
    items.value = [created, ...items.value]
    // „Neue Instanz“ im Aufgaben-Verlauf (Titelleiste).
    useTasksStore().note({ kind: 'create', title: created.name, outcome: 'done', instanceId: created.id })
    return created
  }

  async function remove(id: string) {
    await backend.deleteInstance(id)
    items.value = items.value.filter((i) => i.id !== id)
  }

  return { items, unavailable, loading, loaded, error, load, create, remove, forget }
})
