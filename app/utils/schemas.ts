import { z } from 'zod'

// Spiegelt die Regeln aus `trs_core` – der Kern validiert trotzdem immer selbst.

const versionString = z
  .string()
  .min(1)
  .max(64)
  .regex(/^[A-Za-z0-9._+\- ]+$/, 'Enthält ungültige Zeichen')

export const loaderKinds = ['vanilla', 'fabric', 'quilt', 'forge', 'neoforge'] as const

export const newInstanceSchema = z.object({
  name: z.string().trim().min(1, 'Bitte einen Namen eingeben').max(64, 'Maximal 64 Zeichen'),
  gameVersion: versionString,
  loader: z.object({
    kind: z.enum(loaderKinds),
    version: versionString.nullable(),
  }),
})

const resolutionSchema = z.object({
  width: z.number().int().min(320).max(16384),
  height: z.number().int().min(240).max(16384),
})

export const updateInstanceSchema = z.object({
  name: z.string().trim().min(1, 'Bitte einen Namen eingeben').max(64, 'Maximal 64 Zeichen'),
  overrides: z.object({
    maxMemoryMb: z.number().int().min(512).max(131072).nullable(),
    javaPath: z.string().min(1).max(1024).nullable(),
    jvmArgs: z.string().max(4096).nullable(),
    resolution: resolutionSchema.nullable(),
    trsClient: z.boolean().nullable(),
    boost: z.boolean().nullable(),
  }),
})

export const settingsSchema = z
  .object({
    minMemoryMb: z.number().int().min(128),
    maxMemoryMb: z.number().int().min(512).max(131072),
    javaPath: z.string().min(1).max(1024).nullable(),
    jvmArgs: z.string().max(4096),
    resolution: resolutionSchema,
    concurrentDownloads: z.number().int().min(1).max(64),
    closeOnLaunch: z.boolean(),
    showSnapshots: z.boolean(),
    preferDedicatedGpu: z.boolean(),
  })
  .refine((s) => s.minMemoryMb <= s.maxMemoryMb, {
    message: 'Minimum darf nicht über dem Maximum liegen',
    path: ['minMemoryMb'],
  })

export const serverSchema = z.object({
  name: z.string().trim().min(1, 'Bitte einen Namen eingeben').max(64, 'Maximal 64 Zeichen'),
  address: z
    .string()
    .trim()
    .min(1, 'Bitte eine Adresse eingeben')
    .max(260)
    .regex(/^[A-Za-z0-9._-]+(:\d{1,5})?$/, 'Adresse im Format play.example.de oder play.example.de:25565'),
  autoResourcePack: z.boolean(),
})

export function firstIssue(error: z.ZodError): string {
  return error.issues[0]?.message ?? 'Ungültige Eingabe'
}
