import { defineStore } from 'pinia'
import type { Instance, NewInstance } from '~/types'

export const useInstancesStore = defineStore('instances', () => {
  const items = ref<Instance[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load() {
    loading.value = true
    error.value = null
    try {
      items.value = await backend.listInstances()
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }

  async function create(instance: NewInstance) {
    const created = await backend.createInstance(instance)
    items.value = [created, ...items.value]
    return created
  }

  async function remove(id: string) {
    await backend.deleteInstance(id)
    items.value = items.value.filter((i) => i.id !== id)
  }

  return { items, loading, error, load, create, remove }
})
