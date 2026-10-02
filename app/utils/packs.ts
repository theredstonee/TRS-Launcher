// Geteilte Modpacks (API §27) – Schemata und reine Helfer, getestet in tests/packs.test.ts.
// Keine Nuxt-Auto-Imports, damit die Tests sie direkt laden können.
import { z } from 'zod'
import { intlLocale, t } from './i18n'

const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const packId = z.string().regex(/^[A-Za-z0-9_-]{22}$/)
const code = z.string().regex(/^TRS-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/)
const time = z.string().max(40).nullable()
const count = z.number().int().min(0)
const user = z.object({ uuid, name: z.string().max(32) })

export const PACK_DURATIONS = ['1d', '7d', '30d', 'forever'] as const
export type PackDuration = (typeof PACK_DURATIONS)[number]

export const sharedPackSchema = z.object({
  id: packId,
  code,
  url: z.string().max(300).startsWith('https://'),
  name: z.string().max(64),
  summary: z.string().max(300).nullable(),
  packVersion: z.string().max(64),
  revision: z.number().int().min(1),
  mcVersion: z.string().max(64),
  loader: z.object({ kind: z.enum(['vanilla', 'forge', 'neoforge', 'fabric', 'quilt']), version: z.string().max(64).nullable() }),
  modrinthFiles: count,
  ownJars: count,
  otherFiles: count,
  bytes: count,
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  owner: user,
  createdAt: time,
  updatedAt: time,
  expiresAt: time,
})

export const ownPackSchema = sharedPackSchema.extend({
  duration: z.enum(PACK_DURATIONS),
  installs: count,
  sentTo: count,
})

export const myPacksSchema = z.object({
  packs: z.array(ownPackSchema),
  limits: z.object({ active: count, maxActive: count, uploadsToday: count, maxPerDay: count, maxBytes: count }),
})

export const inboxPackSchema = z.object({ pack: sharedPackSchema, from: user, sentAt: time })

export const packSendResultSchema = z.object({ sent: z.array(user), skipped: z.array(uuid) })

export const packLinkSchema = z.object({
  instanceId: z.string().max(64),
  role: z.enum(['shared', 'installed']),
  packId,
  code,
  revision: z.number().int().min(1),
  name: z.string().max(64),
  include: z.array(z.string().max(120)),
})

export const packUpdateInfoSchema = z.object({ instanceId: z.string().max(64), revision: z.number().int().min(0), latest: sharedPackSchema })

export const packUpdateResultSchema = z.object({
  revision: z.number().int().min(1),
  updated: count,
  removed: count,
  kept: z.array(z.string().max(300)),
})

const shareCount = z.number().int().min(0).max(100000)
const sharePlan = {
  token: z.string().regex(/^[a-f0-9]{32}$/),
  downloads: shareCount,
  uploaded: shareCount,
  bytes: z.number().int().min(0),
}

/** `share_pack` lädt nicht mehr hoch: fertiger Plan oder Rückfrage wegen eigener JARs. */
export const sharePackOutcomeSchema = z.discriminatedUnion('status', [
  z.object({ status: z.literal('ready'), ...sharePlan }),
  z.object({ status: z.literal('confirmOwnJars'), files: z.array(z.string().max(300)), ...sharePlan }),
])

/** Live-Ereignisse (`trs-live`) rund um Packs. */
export const packEventSchemas = [
  z.object({ type: z.literal('pack_shared'), pack: sharedPackSchema, from: user, sentAt: time }),
  z.object({ type: z.literal('pack_updated'), pack: sharedPackSchema }),
  z.object({ type: z.literal('pack_removed'), packId }),
] as const

export type SharedPack = z.infer<typeof sharedPackSchema>
export type OwnPack = z.infer<typeof ownPackSchema>
export type MyPacks = z.infer<typeof myPacksSchema>
export type InboxPack = z.infer<typeof inboxPackSchema>
export type PackLink = z.infer<typeof packLinkSchema>
export type PackUpdateInfo = z.infer<typeof packUpdateInfoSchema>
export type PackUpdateResult = z.infer<typeof packUpdateResultSchema>
export type SharePackOutcome = z.infer<typeof sharePackOutcomeSchema>

export interface SharePackOptions {
  name: string
  version: string
  summary: string | null
  include: string[]
  duration: PackDuration
  allowOwnJars: boolean
}

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/** Eingabe → `TRS-XXXX-XXXX` (wie der Kern: Groß/klein egal, Striche/Leerzeichen, O→0, I/L→1) oder `null`. */
export function normalizePackCode(input: string): string | null {
  let s = input.trim().toUpperCase().replace(/[\s-]/g, '')
  // Ganzer Link eingefügt? Dann den Code dahinter nehmen.
  const fromLink = /\/P\/(TRS[0-9A-Z]{8})$/.exec(s)
  if (fromLink) s = fromLink[1]!
  if (s.length === 11 && s.startsWith('TRS')) s = s.slice(3)
  s = s.replace(/O/g, '0').replace(/[IL]/g, '1')
  if (s.length !== 8 || [...s].some((c) => !ALPHABET.includes(c))) return null
  return `TRS-${s.slice(0, 4)}-${s.slice(4)}`
}

/** „läuft ab am 3. Okt.“ / „läuft nicht ab“. */
export function packExpiry(expiresAt: string | null): string {
  if (!expiresAt) return t('packs.expiry.never')
  const d = new Date(expiresAt)
  if (Number.isNaN(d.getTime())) return t('packs.expiry.never')
  return t('packs.expiry.until', { date: d.toLocaleDateString(intlLocale(), { day: 'numeric', month: 'short', year: 'numeric' }) })
}

export function durationLabel(d: PackDuration): string {
  return t(`packs.duration.${d}`)
}

const LOADER_NAMES: Record<SharedPack['loader']['kind'], string> = {
  vanilla: 'Vanilla',
  forge: 'Forge',
  neoforge: 'NeoForge',
  fabric: 'Fabric',
  quilt: 'Quilt',
}

/** „Minecraft 1.21.1 · Fabric 0.16.9“. */
export function packVersionLine(p: Pick<SharedPack, 'mcVersion' | 'loader'>): string {
  const loader = LOADER_NAMES[p.loader.kind]
  const withVersion = p.loader.kind !== 'vanilla' && p.loader.version ? `${loader} ${p.loader.version}` : loader
  return `Minecraft ${p.mcVersion} · ${withVersion}`
}
