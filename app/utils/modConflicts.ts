// Mod-Konflikt-Helfer: Daten vom Kern (`depcheck::conflict_report`) und die
// Texte dazu. Reine Funktionen – der Store (`stores/modConflicts.ts`) führt aus.
import { z } from 'zod'
import { t } from './i18n'

/** Fehlercode, mit dem der Kern einen Start wegen Mod-Konflikten stoppt. */
export const MOD_CONFLICT_CODE = 'launcher.modVersionsConflict'

const text = (max: number) => z.string().max(max)

export const conflictPartySchema = z.object({
  /** `mod` (Datei), `game` (Minecraft), `loader`, `bundled` (kommt mit dem TRS Client) */
  kind: z.enum(['mod', 'game', 'loader', 'bundled']),
  modId: text(128),
  name: text(256),
  version: text(256),
  fileName: text(512).nullable(),
  bundledIn: text(256).nullable(),
  projectId: text(64).nullable(),
  platform: z.enum(['modrinth', 'curseforge']).nullable(),
  slug: text(256).nullable(),
  // Symbole sind CDN-URLs oder kleine data:-PNGs aus dem Jar (ModIcon prüft selbst).
  iconUrl: text(2_000_000).nullable(),
  enabled: z.boolean(),
  adjustable: z.boolean(),
})
export type ConflictParty = z.infer<typeof conflictPartySchema>

export const modConflictSchema = z.object({
  /** `depends`: verlangt `other` in `ranges`; `breaks`: schließt `ranges` aus; `oldBuild`: für ein älteres Minecraft gebaut */
  kind: z.enum(['depends', 'breaks', 'oldBuild']),
  declarer: conflictPartySchema,
  other: conflictPartySchema,
  ranges: z.array(text(256)).max(8),
  text: text(1024),
})
export type ModConflict = z.infer<typeof modConflictSchema>

export const conflictReportSchema = z.object({
  conflicts: z.array(modConflictSchema).max(500),
  blocksLaunch: z.boolean(),
})
export type ConflictReport = z.infer<typeof conflictReportSchema>

export const fitResultSchema = z.object({
  status: z.enum(['installed', 'none', 'unsupported']),
  title: text(256),
  from: text(256).nullable(),
  to: text(256).nullable(),
})
export type FitResult = z.infer<typeof fitResultSchema>

/** Hat der Kern den Start wegen Mod-Konflikten gestoppt? (BackendError oder `{ code }`) */
export function isModConflictError(e: unknown): boolean {
  return typeof e === 'object' && e !== null && (e as { code?: unknown }).code === MOD_CONFLICT_CODE
}

/** „1.21.x“, „>=0.16 oder <0.15“ – leere Liste bzw. `*` = jede Version. */
export function formatRanges(ranges: string[]): string {
  const list = ranges.map((r) => r.trim()).filter((r) => r && r !== '*')
  return list.length ? list.join(t('modConflicts.or')) : t('modConflicts.anyVersion')
}

/** Name einer Seite, wie er im Satz steht („Fabric API (in Sodium)“). */
export function partyName(p: ConflictParty): string {
  if (p.kind === 'bundled') return t('modConflicts.inTrsClient', { name: p.name })
  if (p.bundledIn) return t('modConflicts.inBundle', { name: p.name, bundle: p.bundledIn })
  return p.name
}

/** Der Konflikt in Spielersprache. */
export function conflictSentence(c: ModConflict): string {
  const params = {
    name: c.declarer.name,
    version: c.declarer.version,
    other: partyName(c.other),
    present: c.other.version,
    required: formatRanges(c.ranges),
  }
  if (c.kind === 'oldBuild') return t('modConflicts.text.oldBuild', params)
  if (c.kind === 'breaks') return t('modConflicts.text.breaks', params)
  if (c.other.kind === 'game') return t('modConflicts.text.game', params)
  if (c.other.kind === 'loader') return t('modConflicts.text.loader', params)
  return t('modConflicts.text.depends', params)
}

/** Schlüssel einer Mod-Datei (je Datei höchstens einmal in der Liste). */
export function partyKey(p: ConflictParty): string {
  return p.fileName ?? `${p.kind}:${p.modId}`
}

/** Mods eines Konflikts, an denen man etwas tun kann (Dateien der Instanz). */
export function actionableParties(c: ModConflict): ConflictParty[] {
  const list = [c.declarer]
  if (c.other.kind === 'mod' && c.other.fileName && c.other.fileName !== c.declarer.fileName) list.push(c.other)
  return list.filter((p) => !!p.fileName)
}

/**
 * „Alle betroffenen deaktivieren“: je Konflikt die Mod, die die Bedingung stellt
 * (die passt nicht – Minecraft, Loader und die verlangte Mod bleiben).
 */
export function filesToDisable(report: ConflictReport): string[] {
  const files: string[] = []
  for (const c of report.conflicts) {
    const file = c.declarer.fileName
    if (file && !files.includes(file)) files.push(file)
  }
  return files
}

/** Text zu „Passende Version suchen“. */
export function fitMessage(r: FitResult): string {
  if (r.status === 'installed') return t('modConflicts.fit.installed', { name: r.title, from: r.from ?? '?', to: r.to ?? '?' })
  if (r.status === 'unsupported') return t('modConflicts.fit.unsupported', { name: r.title })
  return t('modConflicts.fit.none', { name: r.title })
}
