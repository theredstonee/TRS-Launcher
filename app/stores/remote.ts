import { isTauri } from '@tauri-apps/api/core'
import { defineStore } from 'pinia'
import type { LiveEvent } from '~/utils/chat'
import {
  REMOTE_DEBOUNCE_MS,
  REMOTE_HEARTBEAT_MS,
  buildStatus,
  detectRemoteRole,
  newIdempotencyKey,
  resultErrorKey,
  type ClaimedCommand,
  type PairCode,
  type RemoteCommand,
  type RemoteCommandType,
  type RemotePeer,
} from '~/utils/remote'

type RemoteEvent = Extract<LiveEvent, { type: 'remote_command' | 'remote_command_update' | 'remote_status' | 'remote_pairing' }>

/** Ohne Meldung so lange gilt ein PC am Handy als offline (Server: 150 s). */
const ONLINE_MS = 150_000

/** Ein Befehl, den dieses Handy geschickt hat (für Knopf-Zustände und Rückmeldungen). */
export interface SentRemoteCommand {
  desktopId: string
  commandType: RemoteCommandType
  instanceId: string | null
  state: 'pending' | 'running' | 'done' | 'failed'
  error: string | null
  at: number
}

// PC-Fernbedienung (API §33). Am PC: Stand melden (entprellt + Herzschlag), Befehle vom Handy prüfen lassen,
// abholen und ausführen – nur, wenn die Fernbedienung und die Befehlsart eingeschaltet sind. Am Handy: gekoppelte
// PCs mit Status, Befehle schicken, koppeln. Ohne eingeschaltete Fernbedienung tut der PC nichts.
export const useRemoteStore = defineStore('remote', () => {
  const role = detectRemoteRole()
  const settings = useSettingsStore()
  const trs = useTrsStore()

  // --- PC --------------------------------------------------------------------------------
  const pairCode = ref<PairCode | null>(null)
  const phones = ref<RemotePeer[]>([])
  const phonesLoaded = ref(false)
  /** Eigene Geräte-ID (aus `remote_pairings`), um Ereignisse zuzuordnen. */
  const deviceId = ref<string | null>(null)
  const enabled = computed(() => role === 'desktop' && !!settings.current?.remote?.enabled && trs.enabled)
  let started = false
  let throttle: ReturnType<typeof setTimeout> | null = null
  let heartbeat: ReturnType<typeof setInterval> | null = null
  /** Wurde seit dem Start „online“ gemeldet? Dann geht beim Ausschalten einmal „offline“ raus. */
  let publishedOnline = false

  /** Stand aus Bibliothek, laufenden Spielen und Aufgaben. */
  function currentStatus() {
    const games = useGamesStore()
    return buildStatus(
      useInstancesStore().items,
      (id) => games.state(id).phase !== 'idle',
      Object.values(useTasksStore().tasks),
    )
  }

  async function publish() {
    throttle = null
    if (role !== 'desktop' || !trs.enabled) return
    const on = !!settings.current?.remote?.enabled
    if (!on && !publishedOnline) return
    try {
      await backend.remote.publishStatus(on ? currentStatus() : { instances: [], tasks: [] })
      publishedOnline = on
    } catch {
      // Offline o. Ä. – der Herzschlag versucht es wieder.
    }
  }

  /** Höchstens eine Meldung je {@link REMOTE_DEBOUNCE_MS} (Fortschritt ändert sich ständig). */
  function schedule() {
    if (throttle || role !== 'desktop') return
    throttle = setTimeout(() => void publish(), REMOTE_DEBOUNCE_MS)
  }

  async function startPairing() {
    pairCode.value = await backend.remote.pairStart()
    return pairCode.value
  }

  function cancelPairing() {
    if (!pairCode.value) return
    pairCode.value = null
    void backend.remote.pairCancel().catch(() => {})
  }

  async function loadPhones() {
    if (role !== 'desktop' || !trs.enabled) return
    const r = await backend.remote.pairings('desktop')
    deviceId.value = r.deviceId
    phones.value = r.peers
    phonesLoaded.value = true
  }

  async function removePhone(id: string) {
    await backend.remote.unpair('desktop', id)
    phones.value = phones.value.filter((p) => p.id !== id)
  }

  async function result(id: string, ok: boolean, error: string | null = null) {
    await backend.remote.result(id, ok, error).catch(() => {})
  }

  /** Abgeholten Befehl ausführen. Instanz-IDs kommen geprüft aus dem Kern und müssen in der Bibliothek stehen. */
  async function execute(id: string, c: ClaimedCommand, phone: string) {
    const toasts = useToasts()
    const games = useGamesStore()
    const instance = c.instanceId ? useInstancesStore().items.find((i) => i.id === c.instanceId) : undefined
    switch (c.commandType) {
      case 'ping':
        return result(id, true)
      case 'launch_instance': {
        if (!instance) return result(id, false, 'instance_not_found')
        if (games.state(instance.id).phase !== 'idle') return result(id, false, 'already_running')
        if (useTasksStore().installingInstance(instance.id)) return result(id, false, 'busy')
        toasts.info(t('remote.toast.launched', { name: instance.name, phone }))
        const ok = await games.launch(instance.id)
        return result(id, ok, ok ? null : 'failed')
      }
      case 'stop_instance': {
        if (!instance) return result(id, false, 'instance_not_found')
        if (games.state(instance.id).phase !== 'running') return result(id, false, 'not_running')
        toasts.info(t('remote.toast.stopped', { name: instance.name, phone }))
        await games.stop(instance.id)
        return result(id, true)
      }
      case 'install_pack_code': {
        if (!c.code) return result(id, false, 'failed')
        return installPack(id, c.code, phone)
      }
    }
  }

  /**
   * Modpack per Code installieren. Bringt das Pack eigene Mod-Dateien mit (nicht von Modrinth), braucht es das
   * Häkchen „ich vertraue …“ – das gibt es nur am PC: dann öffnet sich dort der Dialog, das Handy erfährt `needs_pc`.
   */
  async function installPack(id: string, code: string, phone: string) {
    const toasts = useToasts()
    const packs = usePacksStore()
    let preview
    try {
      preview = await backend.packs.previewCode(code)
    } catch {
      return result(id, false, 'failed')
    }
    if (preview.pack.ownJars) {
      packs.openCode(code)
      toasts.info(t('remote.toast.packNeedsPc', { name: preview.pack.name, phone }))
      return result(id, false, 'needs_pc')
    }
    const offer = preview.preview.trsClient ?? null
    const trsClient = trsRequest(offer, trsAfterPreview(offer, false, true))
    const pack = preview.pack
    toasts.info(t('remote.toast.installing', { name: pack.name, phone }))
    const run = await useTasksStore().run(
      { key: taskKey('packcode', pack.code), kind: 'modpack', title: pack.name, stage: packStageLabel('pack'), cancellable: true, pausable: true },
      async (ctx) => {
        const created = await backend.packs.installCode(pack.code, trsClient, (pr) => packTaskProgress(ctx, pr), ctx.taskId)
        ctx.update({ instanceId: created.id, doneText: t('tasks.toast.modpackReady', { name: created.name }) })
        await Promise.allSettled([useInstancesStore().load(), packs.loadLinks()])
        return created
      },
    )
    return result(id, run.ok, run.ok ? null : 'failed')
  }

  /** Geprüfter Befehl aus dem Kern: nur mit eingeschalteter Fernbedienung abholen, dann ausführen. */
  async function onCommand(cmd: RemoteCommand) {
    if (!enabled.value) return
    let claimed: ClaimedCommand
    try {
      claimed = await backend.remote.claim(cmd.id)
    } catch {
      // Abgelaufen, schon erledigt oder nicht erlaubt (das Handy erfährt es vom Server).
      return
    }
    await execute(cmd.id, claimed, cmd.phoneName)
    schedule()
  }

  // --- Handy -------------------------------------------------------------------------------
  const pcs = ref<RemotePeer[]>([])
  const pcsLoaded = ref(false)
  const sent = ref<Record<string, SentRemoteCommand>>({})
  const now = ref(Date.now())
  let clock: ReturnType<typeof setInterval> | null = null

  /** PC meldet sich und hat die Fernbedienung an (ohne Meldung seit 150 s: offline). */
  function isOnline(pc: RemotePeer): boolean {
    if (!pc.online) return false
    const at = pc.statusAt ? Date.parse(pc.statusAt) : NaN
    return Number.isFinite(at) ? now.value - at < ONLINE_MS : true
  }

  async function loadPcs() {
    if (!trs.enabled) return
    const r = await backend.remote.pairings('phone')
    deviceId.value = r.deviceId
    pcs.value = r.peers
    pcsLoaded.value = true
  }

  async function pair(code: string) {
    const pc = await backend.remote.pairConfirm(code)
    pcs.value = [...pcs.value.filter((p) => p.id !== pc.id), pc]
    return pc
  }

  async function removePc(id: string) {
    await backend.remote.unpair('phone', id)
    pcs.value = pcs.value.filter((p) => p.id !== id)
  }

  /** Letzter Befehl dieser Art für die Instanz (Knopf zeigt „wird gesendet“/„läuft“). */
  function lastFor(desktopId: string, instanceId: string | null, type?: RemoteCommandType): SentRemoteCommand | null {
    let best: SentRemoteCommand | null = null
    for (const c of Object.values(sent.value)) {
      if (c.desktopId !== desktopId || c.instanceId !== instanceId || (type && c.commandType !== type)) continue
      if (!best || c.at > best.at) best = c
    }
    return best
  }

  async function send(desktopId: string, commandType: RemoteCommandType, args: { instanceId?: string; code?: string } = {}) {
    const c = await backend.remote.send(desktopId, commandType, args, newIdempotencyKey())
    sent.value[c.id] = { desktopId, commandType, instanceId: args.instanceId ?? null, state: c.state, error: null, at: Date.now() }
    return c
  }

  function onUpdate(e: Extract<RemoteEvent, { type: 'remote_command_update' }>) {
    const c = sent.value[e.commandId]
    if (!c) return
    c.state = e.state
    c.error = e.error
    if (e.state === 'failed' && role === 'phone') {
      useToasts().error(t(`remote.result.${resultErrorKey(e.error)}`))
    }
  }

  function onStatus(e: Extract<RemoteEvent, { type: 'remote_status' }>) {
    const pc = pcs.value.find((p) => p.id === e.desktopId)
    if (!pc) return
    pc.online = e.online
    pc.status = e.status
    pc.statusAt = e.at ?? new Date().toISOString()
  }

  function onLiveEvent(e: RemoteEvent) {
    switch (e.type) {
      case 'remote_command':
        if (role === 'desktop') void onCommand(e.command)
        return
      case 'remote_command_update':
        onUpdate(e)
        return
      case 'remote_status':
        onStatus(e)
        return
      case 'remote_pairing':
        // Nur Ereignisse zum eigenen Gerät; die Liste kommt frisch vom Server.
        if (role === 'desktop' && e.desktopId === deviceId.value) {
          if (e.action === 'added') pairCode.value = null
          void loadPhones().catch(() => {})
        } else if (role === 'phone' && e.phoneId === deviceId.value) {
          void loadPcs().catch(() => {})
        }
        return
    }
  }

  /** Einmal beim Start: am PC Stand melden, am Handy die Uhr für „offline“. */
  function start() {
    if (started || !isTauri()) return
    started = true
    if (role === 'phone') {
      clock = setInterval(() => (now.value = Date.now()), 15_000)
      return
    }
    // Ein-/Ausschalten, Rechte, Konto: sofort melden (aus → einmal „offline“).
    watch(
      () => [settings.current?.remote?.enabled, settings.current?.remote?.allowLaunch, settings.current?.remote?.allowInstall, trs.enabled] as const,
      () => {
        if (throttle) clearTimeout(throttle)
        throttle = null
        void publish()
        if (!enabled.value) pairCode.value = null
      },
    )
    // Bibliothek, laufende Spiele, Aufgaben: entprellt.
    watch(
      () => (enabled.value ? JSON.stringify(currentStatus()) : ''),
      (next, before) => {
        if (next && next !== before) schedule()
      },
    )
    heartbeat = setInterval(() => {
      if (enabled.value) void publish()
    }, REMOTE_HEARTBEAT_MS)
  }

  function stop() {
    if (throttle) clearTimeout(throttle)
    if (heartbeat) clearInterval(heartbeat)
    if (clock) clearInterval(clock)
    throttle = heartbeat = clock = null
    started = false
  }

  return {
    role,
    enabled,
    // PC
    pairCode,
    phones,
    phonesLoaded,
    startPairing,
    cancelPairing,
    loadPhones,
    removePhone,
    // Handy
    pcs,
    pcsLoaded,
    sent,
    isOnline,
    loadPcs,
    pair,
    removePc,
    send,
    lastFor,
    // beide
    deviceId,
    onLiveEvent,
    start,
    stop,
  }
})
