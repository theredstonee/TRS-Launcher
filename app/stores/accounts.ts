import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import type { Account, DeviceCode } from '~/types'

export const useAccountsStore = defineStore('accounts', () => {
  const items = ref<Account[]>([])
  const loaded = ref(false)
  const active = computed(() => items.value.find((a) => a.active) ?? null)
  let listening = false

  /** Accounts, die im Spiel (TRS Client) hinzugefügt wurden, sofort zeigen. */
  async function listenChanges() {
    if (listening || !isTauri()) return
    listening = true
    await listen('accounts-changed', () => {
      void load().catch(() => {})
    })
  }

  async function load() {
    void listenChanges()
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
