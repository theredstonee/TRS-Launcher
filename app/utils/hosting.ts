import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { t } from './i18n'
import { trsUserRefSchema } from './trs'
import type { LoaderKind } from '../types'

// Welt-Hosting (API §21): Gehostet wird im Spiel (TRS Client). Der Launcher
// zeigt offene Welten von Freunden, Weltkarten im Chat und Einladungen, tritt
// bei (bzw. fragt an) und startet dann eine passende Instanz. Dem Spiel gibt
// er nur Raum-ID und Code mit – verbinden (P2P/Relay) tut die Mod selbst.

const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const roomId = z.string().regex(/^h[0-9a-f]{20}$/)
const code = z.string().regex(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/)
const loader = z.enum(['vanilla', 'fabric', 'forge', 'neoforge', 'quilt'])
const mcVersion = z.string().regex(/^[0-9A-Za-z][0-9A-Za-z._+ -]{0,31}$/)
const time = z.string().max(40).nullable()

export const hostingMemberSchema = z.object({
  uuid,
  name: z.string().max(16),
  state: z.enum(['invited', 'requested', 'accepted', 'banned']),
  since: time,
})

/** Eine gehostete Welt: `HostRoomView` (eigene) bzw. `RoomView` (Freunde, Einladungen). */
export const hostingRoomSchema = z.object({
  id: roomId,
  code: code.nullable(),
  name: z.string().min(1).max(64),
  host: trsUserRefSchema,
  mcVersion,
  loader,
  maxPlayers: z.number().int().min(2).max(10),
  gameMode: z.enum(['survival', 'creative', 'adventure', 'spectator']),
  pvp: z.boolean(),
  cheats: z.boolean(),
  open: z.boolean(),
  visibility: z.enum(['friends', 'invited']).nullable(),
  players: z.number().int().min(1).max(10),
  createdAt: time,
  expiresAt: time,
  members: z.array(hostingMemberSchema),
  myState: z.enum(['invited', 'requested', 'accepted']).nullable(),
})

/** Weltkarte einer Chat-Nachricht (`MessageView.world`). */
export const chatWorldSchema = z.object({
  roomId,
  code,
  name: z.string().min(1).max(64),
  mcVersion,
  loader,
  host: trsUserRefSchema,
})

export const hostingJoinResultSchema = z.object({
  status: z.enum(['accepted', 'requested']),
  room: hostingRoomSchema,
})

/** Stand der Übergabe ans Spiel (TRS-Link `hostingJoin`). */
export const hostingDeliverySchema = z.discriminatedUnion('state', [
  z.object({ state: z.literal('none') }),
  z.object({ state: z.literal('pending'), roomId }),
  z.object({ state: z.literal('delivered'), roomId }),
  z.object({ state: z.literal('unsupported'), roomId }),
  z.object({ state: z.literal('expired'), roomId }),
])

export const hostingCloseReasons = ['closed', 'expired', 'replaced', 'host_unavailable', 'hidden', 'left'] as const

/** Ereignisse `hosting_*` aus dem Echtzeit-Kanal (ohne `hosting_signal` – das bleibt im Spiel). */
export const hostingEventSchemas = [
  z.object({ type: z.literal('hosting_invite'), room: hostingRoomSchema, from: trsUserRefSchema.nullable() }),
  z.object({ type: z.literal('hosting_invite_revoked'), roomId }),
  z.object({ type: z.literal('hosting_join_request'), roomId, from: trsUserRefSchema }),
  z.object({ type: z.literal('hosting_join_accepted'), room: hostingRoomSchema }),
  z.object({ type: z.literal('hosting_join_declined'), roomId }),
  z.object({ type: z.literal('hosting_kicked'), roomId, banned: z.boolean() }),
  z.object({ type: z.literal('hosting_room'), room: hostingRoomSchema }),
  z.object({ type: z.literal('hosting_room_updated'), room: hostingRoomSchema }),
  z.object({ type: z.literal('hosting_room_closed'), roomId, reason: z.enum(hostingCloseReasons) }),
] as const

export type HostingRoom = z.infer<typeof hostingRoomSchema>
export type HostingMember = z.infer<typeof hostingMemberSchema>
export type ChatWorld = z.infer<typeof chatWorldSchema>
export type HostingJoinResult = z.infer<typeof hostingJoinResultSchema>
export type HostingDelivery = z.infer<typeof hostingDeliverySchema>
export type HostingCloseReason = (typeof hostingCloseReasons)[number]

/** Was der Launcher dem Spiel mitgibt (TRS-Link `hostingJoin`) – keine Tokens. */
export interface HostedWorld {
  roomId: string
  code: string | null
  name: string
  host: { uuid: string; name: string } | null
  mcVersion: string
  loader: LoaderKind
}

/** Ziel von „Beitreten“: Raum-ID (Einladung/Liste) oder Code (Karte/Eingabe). */
export type HostingTarget = { roomId: string; code?: undefined } | { roomId?: undefined; code: string }

// --- Anzeige ------------------------------------------------------------------------

/** `K7QM2X` → `K7Q-M2X`. */
export function formatJoinCode(value: string | null | undefined): string {
  if (!value) return ''
  const plain = value.replace(/[\s-]/g, '').toUpperCase()
  return plain.length === 6 ? `${plain.slice(0, 3)}-${plain.slice(3)}` : plain
}

/** Eingabe → Code (Groß/klein egal, Leerzeichen und Striche ignoriert) oder `null`. */
export function parseJoinCode(value: string): string | null {
  const plain = value.replace(/[\s-]/g, '').toUpperCase()
  return code.safeParse(plain).success ? plain : null
}

const loaderNames: Record<LoaderKind, string> = {
  vanilla: 'Vanilla',
  fabric: 'Fabric',
  quilt: 'Quilt',
  forge: 'Forge',
  neoforge: 'NeoForge',
}

/** „1.21.11 Fabric“ */
export function worldVersionLabel(w: Pick<ChatWorld, 'mcVersion' | 'loader'>): string {
  return `${w.mcVersion} ${loaderNames[w.loader]}`
}

/** Satz zum Schließen einer Welt (Toast/Hinweis). */
export function hostingClosedText(reason: HostingCloseReason, name: string | null): string {
  const world = name ?? t('social.hosting.aWorld')
  switch (reason) {
    case 'expired':
    case 'host_unavailable':
      return t('social.hosting.closed.gone', { world })
    case 'left':
      return t('social.hosting.closed.left', { world })
    case 'hidden':
      return t('social.hosting.closed.hidden', { world })
    default:
      return t('social.hosting.closed.closed', { world })
  }
}

/** Die Anweisung fürs Spiel aus einer Welt (Liste/Einladung) bzw. einer Karte. */
export function hostedWorldFrom(source: HostingRoom | ChatWorld, fallbackCode: string | null = null): HostedWorld {
  const isRoom = 'id' in source
  return {
    roomId: isRoom ? source.id : source.roomId,
    code: source.code ?? fallbackCode,
    name: source.name,
    host: { uuid: source.host.uuid, name: source.host.name },
    mcVersion: source.mcVersion,
    loader: source.loader,
  }
}

// --- Passende Instanz -----------------------------------------------------------------

export interface HostingInstanceLike {
  id: string
  gameVersion: string
  loader: { kind: LoaderKind }
  overrides?: { trsClient?: boolean | null; boost?: boolean | null } | null
  lastPlayed?: string | null
}

/**
 * Loader-Verträglichkeit wie im Kern: gleicher Loader – Vanilla und Fabric
 * gelten als gleich (Vanilla läuft mit der TRS-Optimierung als Fabric).
 */
export function loadersCompatible(room: LoaderKind, instance: LoaderKind): boolean {
  if (room === instance) return true
  const fabricLike = (l: LoaderKind) => l === 'vanilla' || l === 'fabric'
  return fabricLike(room) && fabricLike(instance)
}

/** Kann diese Instanz der Welt beitreten? (Version, Loader, TRS Client an; Vanilla braucht die Optimierung.) */
export function canJoinWith(instance: HostingInstanceLike, world: Pick<HostedWorld, 'mcVersion' | 'loader'>): boolean {
  if (instance.gameVersion !== world.mcVersion) return false
  if (!loadersCompatible(world.loader, instance.loader.kind)) return false
  if (instance.overrides?.trsClient === false) return false
  if (instance.loader.kind === 'vanilla' && instance.overrides?.boost === false) return false
  return true
}

/** Passende Instanzen, beste zuerst: gleicher Loader vor Vanilla↔Fabric, dann zuletzt gespielt. */
export function matchingInstances<T extends HostingInstanceLike>(instances: readonly T[], world: Pick<HostedWorld, 'mcVersion' | 'loader'>): T[] {
  const played = (i: T) => (i.lastPlayed ? Date.parse(i.lastPlayed) || 0 : 0)
  return instances
    .filter((i) => canJoinWith(i, world))
    .sort((a, b) => Number(b.loader.kind === world.loader) - Number(a.loader.kind === world.loader) || played(b) - played(a))
}

/** Name einer schnell angelegten Instanz („Insel (1.21.11 Fabric)“), höchstens 48 Zeichen. */
export function quickInstanceName(world: Pick<HostedWorld, 'name' | 'mcVersion' | 'loader'>): string {
  const label = worldVersionLabel(world)
  const base = world.name.replace(/[^\p{L}\p{N} .,'!?&()+_-]/gu, '').trim().slice(0, 48 - label.length - 3).trim()
  return base ? `${base} (${label})` : label
}

// --- Liste „Welten von Freunden“ -------------------------------------------------------

/** Neueste zuerst; eigene Einladungen/Anfragen vor fremden offenen Welten. */
export function sortRooms(rooms: readonly HostingRoom[]): HostingRoom[] {
  const rank = (r: HostingRoom) => (r.myState === 'accepted' ? 0 : r.myState === 'invited' ? 1 : r.myState === 'requested' ? 2 : 3)
  return [...rooms].sort((a, b) => rank(a) - rank(b) || (b.createdAt ?? '').localeCompare(a.createdAt ?? '') || a.id.localeCompare(b.id))
}

/** Liste nach einem Ereignis aktualisieren (ersetzen/einfügen). */
export function upsertRoom(rooms: readonly HostingRoom[], room: HostingRoom): HostingRoom[] {
  return sortRooms([...rooms.filter((r) => r.id !== room.id), room])
}

/** Welt ist voll (Host zählt mit). */
export function roomFull(room: Pick<HostingRoom, 'players' | 'maxPlayers'>): boolean {
  return room.players >= room.maxPlayers
}
