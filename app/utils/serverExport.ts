import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { t, type MessageKey } from './i18n'

// Instanz als Minecraft-Server exportieren (ZIP und/oder lokal) und lokale
// Server steuern. Antworten des Kerns werden hier mit zod geprüft; die
// Formular-Regeln spiegeln `check_options` im Kern (server_export/mod.rs).

/** Offizielle Minecraft-EULA – der Nutzer muss sie selbst lesen und annehmen. */
export const MINECRAFT_EULA_URL = 'https://aka.ms/MinecraftEULA'

const loader = z.enum(['vanilla', 'fabric', 'quilt', 'forge', 'neoforge'])
const text = (max: number) => z.string().max(max)
const fileName = z.string().min(1).max(120)
const serverId = z.string().regex(/^[a-z0-9_][a-z0-9_-]{0,63}$/)

export const modSides = ['client', 'server', 'both', 'unknown'] as const
export type ModSide = (typeof modSides)[number]

export const serverModSchema = z.object({
  fileName,
  name: text(200),
  side: z.enum(modSides),
  source: z.enum(['trs', 'list', 'jar', 'modrinth', 'curseforge', 'none']),
  included: z.boolean(),
  locked: z.boolean(),
  size: z.number().int().min(0),
})
export type ServerMod = z.infer<typeof serverModSchema>

export const serverExportPlanSchema = z.object({
  suggestedName: text(200),
  gameVersion: text(64),
  loader,
  loaderVersion: text(64).nullable(),
  supported: z.boolean(),
  javaMajor: z.number().int().min(0).max(1000),
  defaultRamMb: z.number().int().min(512).max(131072),
  mods: z.array(serverModSchema).max(2000),
  worlds: z.array(z.object({ folder: fileName, name: text(200) })).max(1000),
  configDirs: z.array(text(64)).max(20),
  offline: z.boolean(),
})
export type ServerExportPlan = z.infer<typeof serverExportPlanSchema>

export const serverStateSchema = z.enum(['starting', 'running', 'stopping', 'stopped'])
export type LocalServerState = z.infer<typeof serverStateSchema>

export const serverStatusSchema = z.object({
  state: serverStateSchema,
  players: z.number().int().min(0).nullable(),
  playerNames: z.array(text(16)).max(1000),
  maxPlayers: z.number().int().min(0),
  startedAt: z.string().max(40).nullable(),
  exitCode: z.number().int().nullable(),
})
export type LocalServerStatus = z.infer<typeof serverStatusSchema>

export const localServerSchema = z.object({
  id: serverId,
  name: text(200),
  gameVersion: text(64),
  loader,
  loaderVersion: text(64).nullable(),
  port: z.number().int().min(1).max(65535),
  ramMb: z.number().int().min(0),
  javaMajor: z.number().int().min(0),
  eulaAccepted: z.boolean(),
  status: serverStatusSchema,
})
export type LocalServer = z.infer<typeof localServerSchema>

const userErrorSchema = z.object({
  kind: z.string().max(64),
  code: z.string().max(128),
  params: z.record(z.string(), z.string()).optional(),
  message: z.string().max(2000),
})

export const serverExportResultSchema = z.object({
  zipFile: text(300).nullable(),
  zipBytes: z.number().int().min(0),
  local: localServerSchema.nullable(),
  mods: z.number().int().min(0),
  leftOut: z.number().int().min(0),
  startError: userErrorSchema.nullable(),
})
export type ServerExportResult = z.infer<typeof serverExportResultSchema>

/** Ereignis `local-server` (Log-Zeilen bzw. neuer Zustand). */
export const localServerEventSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('logs'), id: serverId, lines: z.array(z.string().max(5000)).max(5000) }),
  z.object({ type: z.literal('status'), id: serverId, status: serverStatusSchema }),
])
export type LocalServerEvent = z.infer<typeof localServerEventSchema>

export interface ServerExportProgress {
  phase: 'java' | 'server' | 'files' | 'zip' | 'start'
  percent: number
}

/** Was an `export_server` geht. */
export interface ServerExportOptions {
  name: string
  mods: string[]
  includeConfigs: boolean
  world: string | null
  port: number
  motd: string
  maxPlayers: number
  onlineMode: boolean
  ramMb: number
  eulaAccepted: boolean
  zip: boolean
  local: boolean
}

function err(key: MessageKey) {
  return { error: () => t(key) }
}

/** Spiegelt `check_options` im Kern – der Kern prüft trotzdem selbst. */
export const serverExportOptionsSchema = z
  .object({
    name: z.string().trim().min(1, err('errors.serverExport.nameRequired')).max(48),
    mods: z.array(fileName).max(1500),
    includeConfigs: z.boolean(),
    world: fileName.nullable(),
    port: z.number().int(err('errors.serverExport.invalidPort')).min(1024, err('errors.serverExport.invalidPort')).max(65535, err('errors.serverExport.invalidPort')),
    motd: z.string().max(100),
    maxPlayers: z
      .number()
      .int(err('errors.serverExport.invalidMaxPlayers'))
      .min(1, err('errors.serverExport.invalidMaxPlayers'))
      .max(1000, err('errors.serverExport.invalidMaxPlayers')),
    onlineMode: z.boolean(),
    ramMb: z.number().int().min(512, err('serverExport.ramRange')).max(131072, err('serverExport.ramRange')),
    eulaAccepted: z.boolean(),
    zip: z.boolean(),
    local: z.boolean(),
  })
  .refine((o) => o.zip || o.local, err('errors.serverExport.noTarget'))
  .refine((o) => !o.local || o.eulaAccepted, err('errors.serverExport.eulaRequired'))

/** Ab Werk gewählte Mods (Client-Mods und der TRS Client nicht). */
export function defaultServerMods(mods: ServerMod[]): string[] {
  return mods.filter((m) => m.included && !m.locked).map((m) => m.fileName)
}

/** Teilt die Mods für die Anzeige: was auf den Server kommt und was wegbleibt. */
export function splitMods(mods: ServerMod[], selected: string[]): { server: ServerMod[]; leftOut: ServerMod[] } {
  const chosen = new Set(selected)
  const byName = (a: ServerMod, b: ServerMod) => a.name.localeCompare(b.name)
  return {
    server: mods.filter((m) => chosen.has(m.fileName)).sort(byName),
    leftOut: mods.filter((m) => !chosen.has(m.fileName)).sort(byName),
  }
}

/** Beschriftung der Seite („Client“, „Beide“, „?“). */
export function modSideLabel(side: ModSide): string {
  return t(`serverExport.side.${side}`)
}

/** Woher die Einordnung stammt (Tooltip). */
export function modSourceLabel(source: ServerMod['source']): string {
  return t(`serverExport.source.${source}`)
}

/** Hängt neue Log-Zeilen an und kürzt auf `max` (ältere fallen weg). */
export function appendLog(lines: string[], added: string[], max = 5000): string[] {
  const all = lines.concat(added)
  return all.length > max ? all.slice(all.length - max) : all
}

/** Server-Befehl wie im Kern bereinigt (`/` vorne weg); `null` = ungültig. */
export function cleanCommand(input: string): string | null {
  const text = input.trim().replace(/^\/+/, '').trim()
  // eslint-disable-next-line no-control-regex
  if (!text || [...text].length > 256 || /[\u0000-\u001f\u007f]/.test(text)) return null
  return text
}
