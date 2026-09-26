import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (Instanzen, Spiele,
// Toasts und Router kommen in der App über Nuxts Auto-Imports, im Test als Attrappen).
import { backend, errorMessage } from '../utils/backend'
import type { LiveEvent } from '../utils/chat'
import {
  type ChatWorld,
  type HostedWorld,
  type HostingDelivery,
  type HostingRoom,
  type PrepareProgress,
  type RoomContent,
  hostedWorldFrom,
  hostingClosedText,
  matchingInstances,
  rankByMods,
  quickInstanceName,
  sortRooms,
  upsertRoom,
  worldVersionLabel,
} from '../utils/hosting'
import { t } from '../utils/i18n'
import { useSocialToasts } from './socialToasts'

/** So oft wird nach dem Start gefragt, ob das Spiel den Beitritt schon hat. */
const DELIVERY_POLL_MS = 2_000
/** Länger wartet der Launcher nicht (der Kern verwirft den Beitritt nach 10 Minuten). */
const DELIVERY_GIVE_UP_MS = 11 * 60_000

/** Auswahl, mit welcher Instanz beigetreten wird (leere Liste = anlegen anbieten). */
export interface HostingChoice {
  world: HostedWorld
  instanceIds: string[]
}

/**
 * Welt mit Mods: Dialog mit der Mod-Liste (Pflicht/optional, Quelle, Größe, vorhanden)
 * und der Wahl „Neue Instanz“ / „Vorhandene als Kopie ergänzen“ / „Ohne Mods“.
 */
export interface HostingModsChoice {
  world: HostedWorld
  room: HostingRoom | null
  content: RoomContent | null
  loading: boolean
  error: string | null
  /** Passende Instanzen, die mit den wenigsten fehlenden Mods zuerst. */
  instanceIds: string[]
  /** SHA-1 der Mods je Instanz. */
  present: Record<string, string[]>
}

/** Übergabe ans Spiel, solange sie läuft (für Hinweise in der Oberfläche). */
export interface HostingHandoff {
  instanceId: string
  world: HostedWorld
  state: HostingDelivery['state'] | 'starting'
}

// Welt-Hosting auf der Launcher-Seite: Welten von Freunden (Liste + Echtzeit),
// die eigene Welt (nur lesen – verwaltet wird im Spiel), Beitreten bzw.
// Anfragen, Warten auf die Zustimmung des Hosts und das Starten der passenden
// Instanz mit der Anweisung „tritt dieser Welt bei“ über den TRS-Link.
export const useHostingStore = defineStore('hosting', () => {
  const rooms = ref<HostingRoom[]>([])
  /** Eigene offene Welt (wenn man gerade im Spiel hostet). */
  const mine = ref<HostingRoom | null>(null)
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  /** Hier gestellte Anfragen: Nach der Zustimmung startet der Launcher selbst. */
  const waiting = ref<Record<string, HostedWorld>>({})
  /** Gerade beim Beitreten (Knopf gesperrt). */
  const busy = ref<Record<string, boolean>>({})
  const choice = ref<HostingChoice | null>(null)
  const modsChoice = ref<HostingModsChoice | null>(null)
  /** Fortschritt beim Vorbereiten einer Instanz mit Mods. */
  const preparing = ref<PrepareProgress | null>(null)
  const handoff = ref<HostingHandoff | null>(null)
  let listening = false
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let refreshTimer: ReturnType<typeof setTimeout> | null = null

  const invitedCount = computed(() => rooms.value.filter((r) => r.myState === 'invited').length)

  async function load() {
    loading.value = true
    error.value = null
    const [friends, own] = await Promise.allSettled([backend.hosting.friendsRooms(), backend.hosting.myRooms()])
    if (friends.status === 'fulfilled') rooms.value = sortRooms(friends.value)
    else error.value = errorMessage(friends.reason)
    if (own.status === 'fulfilled') mine.value = own.value[0] ?? null
    loaded.value = friends.status === 'fulfilled' || loaded.value
    loading.value = false
  }

  /** Nach Änderungen, die ein Ereignis nicht ganz beschreibt, kurz darauf neu laden. */
  function refreshSoon() {
    if (!loaded.value) return
    if (refreshTimer) clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => {
      refreshTimer = null
      void load()
    }, 500)
  }

  function setRoom(room: HostingRoom) {
    rooms.value = upsertRoom(rooms.value, room)
  }

  function dropRoom(id: string) {
    rooms.value = rooms.value.filter((r) => r.id !== id)
  }

  function forget(id: string) {
    const { [id]: _, ...rest } = waiting.value
    waiting.value = rest
  }

  // --- Beitreten ------------------------------------------------------------------------

  /**
   * „Beitreten“/„Anfragen“ – aus der Liste (Welt) oder von einer Weltkarte/Eingabe (Code).
   * Eingeladen → sofort drin und Spiel starten; sonst Anfrage stellen und auf den Host warten.
   */
  async function join(source: { room: HostingRoom } | { card: ChatWorld } | { code: string }): Promise<void> {
    const key = 'room' in source ? source.room.id : 'card' in source ? source.card.roomId : source.code
    if (busy.value[key]) return
    busy.value = { ...busy.value, [key]: true }
    try {
      const target = 'room' in source ? { roomId: source.room.id } : { code: 'card' in source ? source.card.code : source.code }
      const result = await backend.hosting.join(target)
      setRoom(result.room)
      const knownCode = 'card' in source ? source.card.code : 'code' in source ? source.code : null
      const world = hostedWorldFrom(result.room, knownCode)
      if (result.status === 'accepted') {
        forget(result.room.id)
        await start(world, result.room)
        return
      }
      waiting.value = { ...waiting.value, [result.room.id]: world }
      useToasts().info(t('social.hosting.requestSent', { host: result.room.host.name }))
    } catch (e) {
      useToasts().error(e)
    } finally {
      const { [key]: _, ...rest } = busy.value
      busy.value = rest
    }
  }

  /** Anfrage zurückziehen, Einladung ablehnen oder Welt verlassen. */
  async function leave(roomId: string) {
    try {
      await backend.hosting.leave(roomId)
      forget(roomId)
      const room = rooms.value.find((r) => r.id === roomId)
      // Offene Welten von Freunden bleiben sichtbar, nur ohne eigenen Stand.
      if (room && room.open && room.myState !== 'accepted') setRoom({ ...room, myState: null })
      else dropRoom(roomId)
    } catch (e) {
      useToasts().error(e)
    }
  }

  /**
   * Drin: passende Instanz wählen (laufende zuerst) oder nachfragen. Teilt die Welt Mods,
   * kommt immer der Mod-Dialog (bei jedem Beitritt).
   */
  async function start(world: HostedWorld, room: HostingRoom | null = null) {
    const known = room ?? rooms.value.find((r) => r.id === world.roomId) ?? null
    if (known?.content && known.content.mods > 0) {
      await openMods(world, known)
      return
    }
    const instances = useInstancesStore()
    const games = useGamesStore()
    if (!instances.loaded) await instances.load()
    const candidates = matchingInstances(instances.items, world)
    const running = candidates.find((i) => games.state(i.id).phase === 'running')
    if (running) {
      await launchInto(running.id, world)
      return
    }
    if (candidates.length === 1) {
      await launchInto(candidates[0]!.id, world)
      return
    }
    choice.value = { world, instanceIds: candidates.map((i) => i.id) }
  }

  function cancelChoice() {
    choice.value = null
  }

  // --- Welten mit Mods ------------------------------------------------------------------

  /** Mod-Liste holen und mit den passenden Instanzen abgleichen (je Instanz die SHA-1 ihrer Mods). */
  async function openMods(world: HostedWorld, room: HostingRoom | null) {
    modsChoice.value = { world, room, content: null, loading: true, error: null, instanceIds: [], present: {} }
    try {
      const content = await backend.hosting.content(world.roomId)
      const instances = useInstancesStore()
      if (!instances.loaded) await instances.load()
      const candidates = matchingInstances(instances.items, world).slice(0, 20)
      const present: Record<string, string[]> = {}
      await Promise.all(
        candidates.map(async (i) => {
          present[i.id] = await backend.hosting.instanceMods(i.id).catch(() => [])
        }),
      )
      if (modsChoice.value?.world.roomId !== world.roomId) return
      if (!content.mods.length) {
        // Nichts (mehr) zu wählen – normal beitreten.
        modsChoice.value = null
        await start(world, room ? { ...room, content: null } : null)
        return
      }
      const ranked = rankByMods(candidates, content, present)
      modsChoice.value = { ...modsChoice.value, content, loading: false, instanceIds: ranked.map((i) => i.id), present }
    } catch (e) {
      if (modsChoice.value?.world.roomId === world.roomId) modsChoice.value = { ...modsChoice.value, loading: false, error: errorMessage(e) }
    }
  }

  function cancelMods() {
    if (!preparing.value) modsChoice.value = null
  }

  /**
   * Dialog bestätigt: „Ohne Mods“ startet die gewählte Instanz; sonst legt der Kern eine neue
   * Instanz an bzw. kopiert die gewählte, lädt und prüft die Mods – dann Start mit Beitritt.
   */
  async function confirmMods(choiceMade: { mode: 'new' | 'copy' | 'none'; baseInstanceId: string | null; selection: string[]; trust: boolean }) {
    const c = modsChoice.value
    if (!c?.content || preparing.value) return
    if (choiceMade.mode === 'none') {
      if (!choiceMade.baseInstanceId) return
      modsChoice.value = null
      await launchInto(choiceMade.baseInstanceId, c.world)
      return
    }
    preparing.value = { step: 'instance', done: 0, total: 1, name: null, bytes: 0, totalBytes: 0 }
    try {
      const result = await backend.hosting.prepare(
        {
          roomId: c.world.roomId,
          mode: choiceMade.mode,
          baseInstanceId: choiceMade.mode === 'copy' ? choiceMade.baseInstanceId : null,
          name: null,
          mods: choiceMade.selection,
          trustHost: choiceMade.trust,
        },
        (p) => {
          preparing.value = p
        },
      )
      modsChoice.value = null
      await useInstancesStore().load()
      useToasts().ok(t('social.hosting.mods.prepared', { count: result.installed }))
      await launchInto(result.instanceId, c.world)
    } catch (e) {
      useToasts().error(e)
    } finally {
      preparing.value = null
    }
  }

  /** „Im Launcher öffnen“ aus dem Spiel (Ereignis `hosting-open`): Mod-Dialog dieser Welt. */
  async function openFromGame(roomId: string) {
    try {
      const room = await backend.hosting.room(roomId)
      if (!room) {
        useToasts().error(hostingClosedText('closed', null))
        return
      }
      setRoom(room)
      await start(hostedWorldFrom(room), room)
    } catch (e) {
      useToasts().error(e)
    }
  }

  /** Einmal beim Start: auf `hosting-open` aus dem Spiel hören. */
  async function init() {
    if (listening || !isTauri()) return
    listening = true
    await listen<{ roomId: string }>('hosting-open', (e) => void openFromGame(e.payload.roomId))
  }

  /** Auswahl bestätigt: vorhandene Instanz oder (`null`) eine neue anlegen. */
  async function choose(instanceId: string | null) {
    const c = choice.value
    choice.value = null
    if (!c) return
    if (instanceId) await launchInto(instanceId, c.world)
    else await createAndLaunch(c.world)
  }

  /** Neue Instanz mit Version und Loader der Welt (TRS Client ist von selbst an). */
  async function createAndLaunch(world: HostedWorld) {
    try {
      const created = await useInstancesStore().create({
        name: quickInstanceName(world),
        gameVersion: world.mcVersion,
        loader: { kind: world.loader, version: null },
      })
      useToasts().ok(t('social.hosting.instanceCreated', { instance: created.name }))
      await launchInto(created.id, world)
    } catch (e) {
      useToasts().error(e)
    }
  }

  /**
   * Instanz starten (oder, wenn sie läuft, die Anweisung ans laufende Spiel geben).
   * Danach fragt der Launcher den Kern, bis das Spiel den Beitritt abgeholt hat.
   */
  async function launchInto(instanceId: string, world: HostedWorld) {
    const games = useGamesStore()
    const instance = useInstancesStore().items.find((i) => i.id === instanceId)
    const name = instance?.name ?? instanceId
    const phase = games.state(instanceId).phase
    if (phase === 'preparing') {
      useToasts().info(t('social.hosting.alreadyStarting', { instance: name }))
      return
    }
    handoff.value = { instanceId, world, state: 'starting' }
    if (phase === 'running') {
      try {
        await backend.launchInstance(instanceId, null, () => {}, null, null, world)
      } catch (e) {
        handoff.value = null
        useToasts().error(e)
        return
      }
    } else {
      useToasts().info(t('social.hosting.starting', { instance: name, world: world.name }))
      if (!(await games.launch(instanceId, null, null, world))) {
        handoff.value = null
        return
      }
    }
    watchDelivery(instanceId, world, Date.now())
  }

  function stopWatching() {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
  }

  /** Stand der Übergabe verfolgen und einmal Bescheid sagen (übergeben, zu alt, abgelaufen). */
  function watchDelivery(instanceId: string, world: HostedWorld, since: number) {
    stopWatching()
    const tick = async () => {
      pollTimer = null
      let delivery: HostingDelivery
      try {
        delivery = await backend.hosting.delivery(instanceId)
      } catch {
        delivery = { state: 'pending', roomId: world.roomId }
      }
      const current = handoff.value
      if (!current || current.instanceId !== instanceId || current.world.roomId !== world.roomId) return
      const mineNow = delivery.state !== 'none' && delivery.roomId === world.roomId
      if (mineNow && delivery.state === 'delivered') {
        handoff.value = { ...current, state: 'delivered' }
        useToasts().ok(t('social.hosting.handedOver', { world: world.name }))
        return
      }
      if (mineNow && delivery.state === 'unsupported') {
        handoff.value = { ...current, state: 'unsupported' }
        useToasts().error(t('social.hosting.clientTooOld'))
        return
      }
      if ((mineNow && delivery.state === 'expired') || Date.now() - since > DELIVERY_GIVE_UP_MS) {
        handoff.value = { ...current, state: 'expired' }
        useToasts().error(t('social.hosting.notPickedUp'))
        return
      }
      // Spiel beendet, bevor es sich gemeldet hat: nichts mehr zu tun.
      if (delivery.state === 'none' && useGamesStore().state(instanceId).phase === 'idle') {
        handoff.value = null
        return
      }
      handoff.value = { ...current, state: 'pending' }
      pollTimer = setTimeout(() => void tick(), DELIVERY_POLL_MS)
    }
    pollTimer = setTimeout(() => void tick(), DELIVERY_POLL_MS)
  }

  // --- Echtzeit ------------------------------------------------------------------------

  /** Ereignisse `hosting_*` aus dem Echtzeit-Kanal (Liste, eigene Welt, Hinweise). */
  async function onEvent(e: LiveEvent) {
    switch (e.type) {
      case 'hosting_invite': {
        setRoom(e.room)
        const room = e.room
        const from = e.from ?? room.host
        void useSocialToasts().notify('invite', {
          key: `world:${room.id}`,
          title: t('social.hosting.inviteTitle', { name: from.name }),
          body: `${room.name} · ${worldVersionLabel(room)}`,
          face: from,
          open: () => openWorlds(),
          actions: [
            { label: t('social.hosting.join'), primary: true, run: () => void join({ room }) },
            { label: t('social.hosting.decline'), run: () => void leave(room.id) },
          ],
        })
        return
      }
      case 'hosting_invite_revoked': {
        forget(e.roomId)
        const room = rooms.value.find((r) => r.id === e.roomId)
        if (room?.myState === 'invited') setRoom({ ...room, myState: null })
        refreshSoon()
        return
      }
      case 'hosting_join_accepted': {
        setRoom(e.room)
        const world = waiting.value[e.room.id]
        if (world) {
          forget(e.room.id)
          useToasts().ok(t('social.hosting.accepted', { host: e.room.host.name }))
          await start(hostedWorldFrom(e.room, world.code), e.room)
          return
        }
        // Anfrage kam aus dem Spiel (oder einem anderen Gerät): nur Bescheid sagen.
        const room = e.room
        void useSocialToasts().notify('invite', {
          key: `world:${room.id}`,
          title: t('social.hosting.acceptedTitle', { host: room.host.name }),
          body: `${room.name} · ${worldVersionLabel(room)}`,
          face: room.host,
          open: () => openWorlds(),
          actions: [{ label: t('social.hosting.join'), primary: true, run: () => void start(hostedWorldFrom(room), room) }],
        })
        return
      }
      case 'hosting_join_declined': {
        const room = rooms.value.find((r) => r.id === e.roomId)
        const asked = !!waiting.value[e.roomId] || room?.myState === 'requested'
        forget(e.roomId)
        if (room) setRoom({ ...room, myState: null })
        if (asked) useSocialToasts().notice(t('social.hosting.declined', { host: room?.host.name ?? '?' }))
        return
      }
      case 'hosting_kicked': {
        const room = rooms.value.find((r) => r.id === e.roomId)
        forget(e.roomId)
        dropRoom(e.roomId)
        const world = room?.name ?? t('social.hosting.aWorld')
        useSocialToasts().notice(e.banned ? t('social.hosting.banned', { world }) : t('social.hosting.kicked', { world }))
        refreshSoon()
        return
      }
      case 'hosting_room':
        mine.value = e.room
        return
      case 'hosting_room_updated':
        if (mine.value?.id === e.room.id) mine.value = { ...mine.value, ...e.room, code: mine.value.code, members: mine.value.members }
        else setRoom(e.room)
        return
      case 'hosting_room_closed': {
        if (mine.value?.id === e.roomId) {
          mine.value = null
          return
        }
        const room = rooms.value.find((r) => r.id === e.roomId)
        const involved = !!waiting.value[e.roomId] || room?.myState === 'accepted' || room?.myState === 'requested'
        forget(e.roomId)
        dropRoom(e.roomId)
        if (involved && e.reason !== 'left') useSocialToasts().notice(hostingClosedText(e.reason, room?.name ?? null))
        if (handoff.value?.world.roomId === e.roomId && handoff.value.state !== 'delivered') {
          stopWatching()
          handoff.value = null
        }
        return
      }
      default:
        return
    }
  }

  function openWorlds() {
    void backend.social.focusWindow().catch(() => {})
    void useRouter().push({ path: '/social', query: { tab: 'worlds' } })
  }

  /** Abmelden/Account-Wechsel: alles vergessen. */
  function reset() {
    stopWatching()
    rooms.value = []
    mine.value = null
    loaded.value = false
    waiting.value = {}
    busy.value = {}
    choice.value = null
    modsChoice.value = null
    preparing.value = null
    handoff.value = null
  }

  return {
    rooms,
    mine,
    loaded,
    loading,
    error,
    waiting,
    busy,
    choice,
    modsChoice,
    preparing,
    handoff,
    invitedCount,
    load,
    join,
    leave,
    start,
    choose,
    cancelChoice,
    openMods,
    cancelMods,
    confirmMods,
    openFromGame,
    init,
    launchInto,
    onEvent,
    reset,
  }
})
