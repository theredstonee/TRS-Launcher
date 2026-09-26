import { defineStore } from 'pinia'
import type { Server, ServerInput, ServerStatus } from '~/types'

export const useServersStore = defineStore('servers', () => {
  const items = ref<Server[]>([])
  const loaded = ref(false)
  /** `undefined` = wird gerade abgefragt. */
  const statuses = ref<Record<string, ServerStatus | undefined>>({})

  async function load() {
    items.value = await backend.listServers()
    loaded.value = true
    refresh()
  }

  async function ping(id: string) {
    try {
      statuses.value[id] = await backend.pingServer(id)
    } catch {
      delete statuses.value[id]
    }
  }

  /**
   * Fragt alle Server ab, höchstens {@link PING_PARALLEL} gleichzeitig; alte
   * Werte bleiben sichtbar, bis neue da sind.
   */
  function refresh() {
    return runLimited(items.value.map((s) => s.id), PING_PARALLEL, ping)
  }

  async function add(input: ServerInput) {
    const server = await backend.addServer(input)
    items.value.push(server)
    ping(server.id)
    return server
  }

  async function update(id: string, input: ServerInput) {
    const server = await backend.updateServer(id, input)
    items.value = items.value.map((s) => (s.id === id ? server : s))
    delete statuses.value[id]
    ping(id)
  }

  async function remove(id: string) {
    await backend.removeServer(id)
    items.value = items.value.filter((s) => s.id !== id)
    delete statuses.value[id]
  }

  return { items, loaded, statuses, load, refresh, add, update, remove }
})
