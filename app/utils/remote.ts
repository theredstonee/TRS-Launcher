// PC-Fernbedienung (API §33) – Schemata und reine Helfer, getestet in tests/remote.test.ts.
// Keine Nuxt-Auto-Imports, damit die Tests sie direkt laden können.
import { z } from 'zod'
import { detectOs, isMobileOs } from './system'

const deviceId = z.string().regex(/^[A-Za-z0-9_-]{22}$/)
const instanceId = z.string().regex(/^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/)
const time = z.string().max(40).nullable()

export const REMOTE_COMMAND_TYPES = ['launch_instance', 'stop_instance', 'install_pack_code', 'ping'] as const
export type RemoteCommandType = (typeof REMOTE_COMMAND_TYPES)[number]
export const REMOTE_MAX_INSTANCES = 200
export const REMOTE_MAX_TASKS = 10
/** Spätestens so oft meldet der PC seinen Stand (Server: 150 s ohne Meldung = offline). */
export const REMOTE_HEARTBEAT_MS = 60_000
/** Änderungen werden so lange gesammelt, bevor der Stand rausgeht. */
export const REMOTE_DEBOUNCE_MS = 2_000

/**
 * Rolle dieses Geräts: Handy (Android/iPhone/iPad) steuert, PC wird gesteuert. Aus dem User-Agent des Webviews –
 * iPadOS meldet sich wie ein Mac, hat aber Touch.
 */
export function detectRemoteRole(userAgent = globalThis.navigator?.userAgent ?? '', touchPoints = globalThis.navigator?.maxTouchPoints ?? 0): 'desktop' | 'phone' {
  return isMobileOs(detectOs(userAgent, touchPoints)) ? 'phone' : 'desktop'
}

export const statusInstanceSchema = z.object({
  id: instanceId,
  name: z.string().max(64),
  version: z.string().max(32),
  loader: z.string().max(16),
  iconHash: z.string().regex(/^[0-9a-f]{8,64}$/).nullable(),
  running: z.boolean(),
})

export const statusTaskSchema = z.object({
  title: z.string().max(80),
  progress: z.number().min(0).max(1).nullable(),
  instanceId: instanceId.nullable(),
})

export const remoteStatusSchema = z.object({
  online: z.boolean(),
  allow: z.object({ launch: z.boolean(), install: z.boolean() }),
  instances: z.array(statusInstanceSchema).max(REMOTE_MAX_INSTANCES),
  tasks: z.array(statusTaskSchema).max(REMOTE_MAX_TASKS),
})

export const remotePeerSchema = z.object({
  id: deviceId,
  kind: z.enum(['desktop', 'phone']),
  name: z.string().max(48),
  pairedAt: time,
  lastSeenAt: time,
  online: z.boolean(),
  status: remoteStatusSchema.nullable(),
  statusAt: time,
})

export const remotePairingsSchema = z.object({ deviceId, peers: z.array(remotePeerSchema).max(50) })

export const pairCodeSchema = z.object({
  code: z.string().regex(/^[2-9A-HJKMNP-TV-Z]{3}-[2-9A-HJKMNP-TV-Z]{3}$/),
  link: z.string().regex(/^trs-launcher:\/\/remote-pair\/[2-9A-HJKMNP-TV-Z]{6}$/),
  expiresAt: time,
  expiresIn: z.number().int().min(1).max(600),
})

export const sentCommandSchema = z.object({
  id: deviceId,
  desktopId: deviceId,
  commandType: z.enum(REMOTE_COMMAND_TYPES),
  state: z.enum(['pending', 'running', 'done', 'failed']),
  expiresAt: time,
  duplicate: z.boolean(),
})

export const claimedCommandSchema = z.object({
  commandType: z.enum(REMOTE_COMMAND_TYPES),
  instanceId: instanceId.nullable(),
  code: z.string().regex(/^TRS-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/).nullable(),
})

export const remoteCommandSchema = z.object({
  id: deviceId,
  phoneId: deviceId,
  phoneName: z.string().max(48),
  commandType: z.enum(REMOTE_COMMAND_TYPES),
  instanceId: instanceId.nullable(),
  code: z.string().max(16).nullable(),
})

/** Ereignisse aus dem Echtzeit-Kanal (§33.6) – `remote_command` hat der Kern schon geprüft. */
export const remoteEventSchemas = [
  z.object({ type: z.literal('remote_command'), command: remoteCommandSchema }),
  z.object({
    type: z.literal('remote_command_update'),
    commandId: deviceId,
    desktopId: deviceId,
    phoneId: deviceId,
    commandType: z.enum(REMOTE_COMMAND_TYPES),
    state: z.enum(['pending', 'running', 'done', 'failed']),
    error: z.string().max(64).nullable(),
  }),
  z.object({ type: z.literal('remote_status'), desktopId: deviceId, online: z.boolean(), status: remoteStatusSchema, at: time }),
  z.object({
    type: z.literal('remote_pairing'),
    action: z.enum(['added', 'removed']),
    desktopId: deviceId,
    phoneId: deviceId,
    desktop: remotePeerSchema.nullable(),
    phone: remotePeerSchema.nullable(),
  }),
] as const

export type RemoteStatus = z.infer<typeof remoteStatusSchema>
export type StatusInstance = z.infer<typeof statusInstanceSchema>
export type StatusTask = z.infer<typeof statusTaskSchema>
export type RemotePeer = z.infer<typeof remotePeerSchema>
export type RemotePairings = z.infer<typeof remotePairingsSchema>
export type PairCode = z.infer<typeof pairCodeSchema>
export type SentCommand = z.infer<typeof sentCommandSchema>
export type ClaimedCommand = z.infer<typeof claimedCommandSchema>
export type RemoteCommand = z.infer<typeof remoteCommandSchema>

/** Was der PC meldet (`online`/`allow` ergänzt der Kern aus den Einstellungen). */
export interface StatusInput {
  instances: StatusInstance[]
  tasks: StatusTask[]
}

const PAIR_ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ'

/** Eingabe (Code oder QR-Link) → `XXX-XXX`, sonst `null` (wie der Kern). */
export function normalizePairCode(input: string): string | null {
  let s = input.trim()
  if (s.length > 80) return null
  const link = /^trs-launcher:\/\/remote-pair\/([A-Za-z0-9 -]{6,8})\/?$/i.exec(s)
  if (link) s = link[1]!
  else if (s.includes('://')) return null
  const code = s.toUpperCase().replace(/[\s-]/g, '')
  if (code.length !== 6 || [...code].some((c) => !PAIR_ALPHABET.includes(c))) return null
  return `${code.slice(0, 3)}-${code.slice(3)}`
}

/** Zufälliger Idempotenz-Schlüssel je Tipp (ein Doppel-Tipp schickt denselben Befehl nicht zweimal). */
export function newIdempotencyKey(): string {
  const bytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(bytes)
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** Kurzer Hash (16 Hex) für das Instanz-Symbol – das Handy erkennt daran Änderungen, ohne den Pfad zu kennen. */
export function iconHash(icon: string | null | undefined): string | null {
  if (!icon) return null
  // FNV-1a 64 Bit, zwei Hälften – reicht als Wiedererkennungsmerkmal, kein Geheimnis.
  let h1 = 0x811c9dc5
  let h2 = 0x01000193
  for (let i = 0; i < icon.length; i++) {
    const c = icon.charCodeAt(i)
    h1 = Math.imul(h1 ^ c, 0x01000193) >>> 0
    h2 = Math.imul(h2 ^ c, 0x811c9dc5) >>> 0
  }
  return h1.toString(16).padStart(8, '0') + h2.toString(16).padStart(8, '0')
}

/** Minimal-Ansicht einer Instanz für den Status. */
export interface StatusSourceInstance {
  id: string
  name: string
  gameVersion: string
  /** Launcher: `{ kind, version }`; reicht auch als Text. */
  loader: string | { kind: string }
  icon?: string | null
}

/** Minimal-Ansicht einer laufenden Aufgabe für den Status. */
export interface StatusSourceTask {
  title: string
  percent: number | null
  instanceId: string | null
  status: string
}

/**
 * Stand für die Handys bauen: Instanzen (gültige IDs, Texte gekürzt, „läuft“), laufende Aufgaben mit Fortschritt
 * 0–1. Zuletzt gespielte Instanzen zuerst (die Liste ist schon so sortiert); höchstens 200 bzw. 10.
 */
export function buildStatus(
  instances: StatusSourceInstance[],
  isRunning: (id: string) => boolean,
  tasks: StatusSourceTask[],
): StatusInput {
  const valid = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/
  const seen = new Set<string>()
  const out: StatusInstance[] = []
  for (const i of instances) {
    if (out.length >= REMOTE_MAX_INSTANCES) break
    if (!valid.test(i.id) || seen.has(i.id)) continue
    seen.add(i.id)
    out.push({
      id: i.id,
      name: [...i.name].slice(0, 64).join(''),
      version: i.gameVersion.slice(0, 32),
      loader: (typeof i.loader === 'string' ? i.loader : i.loader.kind).slice(0, 16),
      iconHash: iconHash(i.icon),
      running: isRunning(i.id),
    })
  }
  const running = tasks
    .filter((t) => t.status === 'running')
    .slice(0, REMOTE_MAX_TASKS)
    .map((t) => ({
      title: [...t.title].slice(0, 80).join(''),
      progress: t.percent === null || !Number.isFinite(t.percent) ? null : Math.min(1, Math.max(0, t.percent / 100)),
      instanceId: t.instanceId && valid.test(t.instanceId) ? t.instanceId : null,
    }))
  return { instances: out, tasks: running }
}

/** Fehler-Codes, die der PC als Ergebnis meldet (`remote.result.<code>` in den Übersetzungen). */
export const REMOTE_RESULT_ERRORS = ['instance_not_found', 'already_running', 'not_running', 'busy', 'needs_pc', 'disabled', 'failed'] as const
export type RemoteResultError = (typeof REMOTE_RESULT_ERRORS)[number]

export function resultErrorKey(error: string | null): RemoteResultError {
  return (REMOTE_RESULT_ERRORS as readonly string[]).includes(error ?? '') ? (error as RemoteResultError) : 'failed'
}
