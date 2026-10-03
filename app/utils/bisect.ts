import { z } from 'zod'

// „Schuldige Mod finden“: Antworten des Kerns (`trs_core::bisect`) und kleine
// Helfer für das Fenster. Die Suche selbst (Aufteilen, Wiederherstellen) läuft im Kern.

const sourceSchema = z.object({
  projectId: z.string().max(64),
  versionId: z.string().max(64),
  versionNumber: z.string().max(200).optional(),
  platform: z.enum(['modrinth', 'curseforge']).optional(),
})

export const bisectModSchema = z.object({
  fileName: z.string().max(200),
  title: z.string().nullable(),
  iconUrl: z.string().nullable(),
  source: sourceSchema.nullable(),
  slug: z.string().nullable(),
})
export type BisectMod = z.output<typeof bisectModSchema>

export const bisectViewSchema = z.object({
  instanceId: z.string().max(64),
  startedAt: z.string(),
  round: z.number().int().nonnegative(),
  estimatedRounds: z.number().int().nonnegative(),
  phase: z.enum(['testing', 'found', 'notFound']),
  total: z.number().int().nonnegative(),
  suspects: z.array(bisectModSchema),
  testing: z.number().int().nonnegative(),
  disabled: z.number().int().nonnegative(),
  result: z.array(bisectModSchema),
})
export type BisectView = z.output<typeof bisectViewSchema>

export const bisectModName = (m: BisectMod) => m.title ?? m.fileName

/** Fortschritt in Prozent: wie weit die Verdächtigen schon eingegrenzt sind. */
export function bisectPercent(view: BisectView): number {
  if (view.phase !== 'testing') return 100
  const total = Math.max(1, view.estimatedRounds)
  return Math.min(95, Math.round(((view.round - 1) / total) * 100))
}

/** Antwort auf eine Runde, während das Spiel noch läuft: erst beenden, dann werten. */
export type BisectPending = { failed: boolean }
