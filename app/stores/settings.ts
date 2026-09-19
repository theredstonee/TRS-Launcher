import { defineStore } from 'pinia'
import type { Settings } from '~/types'

export const useSettingsStore = defineStore('settings', () => {
  const current = ref<Settings | null>(null)

  async function load() {
    current.value = await backend.getSettings()
    return current.value
  }

  async function save(settings: Settings) {
    current.value = await backend.updateSettings(settings)
    return current.value
  }

  return { current, load, save }
})
