// Umziehen: Datenordner verschieben und Instanzen an einen eigenen Speicherort.
// Schemas für die Antworten des Kerns und reine Helfer – getestet in tests/relocate.test.ts.
import { z } from 'zod'
import type { DataSource, MovePlan } from '../types'
import type { MessageKey } from './i18n'

const pathText = z.string().min(1).max(4096)

export const movePlanSchema = z.object({
  target: pathText,
  bytes: z.number().int().nonnegative(),
  files: z.number().int().nonnegative(),
  links: z.number().int().nonnegative(),
  free: z.number().int().nonnegative().nullable(),
  sameVolume: z.boolean(),
  enoughSpace: z.boolean(),
})

export const instanceLocationSchema = z.object({
  path: pathText,
  custom: z.boolean(),
  defaultPath: pathText,
})

export const unavailableInstancesSchema = z
  .array(z.object({ id: z.string().min(1).max(64), path: pathText }))
  .max(10_000)

export const pickedFolderSchema = pathText.nullable()

/** Eingetippter Zielordner: nur grob prüfen – streng prüft der Kern. */
export const targetFolderSchema = z
  .string()
  .trim()
  .min(2)
  .max(1024)
  .refine((v) => !/[\u0000-\u001f\u007f]/.test(v))

/** Beschriftung der Herkunft des Datenordners in den Einstellungen. */
export function dataSourceKey(source: DataSource): MessageKey {
  switch (source) {
    case 'custom':
      return 'settings.storage.sourceCustom'
    case 'portable':
      return 'settings.storage.sourcePortable'
    case 'env':
      return 'settings.storage.sourceEnv'
    default:
      return 'settings.storage.sourceDefault'
  }
}

/** Warum der Umzug nicht starten kann (`null` = alles bereit). */
export function moveBlocker(plan: MovePlan, gamesRunning: boolean): MessageKey | null {
  if (gamesRunning) return 'relocate.blockedGames'
  if (!plan.enoughSpace) return 'relocate.blockedSpace'
  return null
}

/** Download-Seite für die portable Version (Updates von Hand; Releases sind Vorabversionen, daher nicht `/latest`). */
export const releasesUrl = 'https://github.com/theredstonee/TRS-Launcher/releases'
