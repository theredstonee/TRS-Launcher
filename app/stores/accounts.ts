import { defineStore } from 'pinia'
import type { Account, DeviceCode } from '~/types'

export const useAccountsStore = defineStore('accounts', () => {
  const items = ref<Account[]>([])
  const loaded = ref(false)
  const active = computed(() => items.value.find((a) => a.active) ?? null)

  async function load() {
    items.value = await backend.listAccounts()
    loaded.value = true
  }

  async function loginBrowser() {
    await backend.loginBrowser()
    await load()
  }

  async function loginDeviceCode(onCode: (code: DeviceCode) => void) {
    await backend.loginDeviceCode(onCode)
    await load()
  }

  async function setActive(id: string) {
    await backend.setActiveAccount(id)
    await load()
  }

  async function remove(id: string) {
    await backend.removeAccount(id)
    await load()
  }

  return { items, loaded, active, load, loginBrowser, loginDeviceCode, setActive, remove }
})
