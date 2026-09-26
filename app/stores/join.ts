import { defineStore } from 'pinia'

/** Version aus dem Server-Status („Paper 1.21.4“ → „1.21.4“). */
export function versionHint(name: string | null | undefined): string | null {
  const match = name ? /\b(\d+\.\d+(?:\.\d+)?)\b/.exec(name) : null
  return match ? match[1]! : null
}

// „Beitreten“ aus einer Server-Einladung: passende Instanz starten und direkt
// verbinden. Gibt es mehrere Instanzen, fragt ein kleiner Dialog, welche
// (vorausgewählt: gleiche Version, sonst zuletzt gespielt).
export const useJoinStore = defineStore('join', () => {
  const pending = ref<{ address: string; version: string | null } | null>(null)

  function launch(instanceId: string, address: string) {
    const instances = useInstancesStore()
    const games = useGamesStore()
    const target = instances.items.find((i) => i.id === instanceId)
    if (!target) return
    if (games.state(target.id).phase !== 'idle') {
      useToasts().info(t('social.invite.alreadyRunning', { instance: target.name }))
      return
    }
    useToasts().info(t('social.invite.starting', { instance: target.name, server: address }))
    void games.launch(target.id, null, address)
  }

  /** Beitreten anfragen – startet sofort oder öffnet die Auswahl. */
  async function request(address: string, version: string | null) {
    const instances = useInstancesStore()
    if (!instances.items.length) await instances.load().catch(() => {})
    const list = instances.items
    if (!list.length) {
      useToasts().error(t('social.invite.noInstance'))
      return
    }
    if (list.length === 1) {
      launch(list[0]!.id, address)
      return
    }
    pending.value = { address, version }
  }

  function cancel() {
    pending.value = null
  }

  function confirm(instanceId: string) {
    const p = pending.value
    pending.value = null
    if (p) launch(instanceId, p.address)
  }

  return { pending, request, cancel, confirm }
})
