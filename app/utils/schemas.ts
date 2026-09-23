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

// Steuerzeichen (auch Zeilenumbrüche) würden in `cmd /C` weitere Befehle anhängen.
// eslint-disable-next-line no-control-regex
const noControl = /^[^\u0000-\u001f\u007f]*$/

export const javaPathSchema = z
  .string()
  .max(1024)
  .regex(noControl, 'Java-Pfad ist ungültig')
  .regex(/^[A-Za-z]:\\.*\\javaw?\.exe$/i, 'Der Java-Pfad muss auf java.exe oder javaw.exe zeigen')

const hookCommand = z
  .string()
  .trim()
  .max(1024, 'Befehle: höchstens 1024 Zeichen')
  .regex(noControl, 'Befehl enthält ungültige Zeichen')
  .nullable()
  .transform((v) => (v ? v : null))

export const envVarSchema = z.object({
  key: z
    .string()
    .trim()
    .max(64, 'Variablennamen: höchstens 64 Zeichen')
    .regex(/^[A-Za-z_][A-Za-z0-9_]*$/, 'Variablennamen: nur Buchstaben, Ziffern und _ (nicht vorne)'),
  value: z.string().max(1024, 'Werte: höchstens 1024 Zeichen').regex(noControl, 'Wert enthält ungültige Zeichen'),
})

export const hooksSchema = z.object({
  preLaunch: hookCommand,
  wrapper: hookCommand,
  postExit: hookCommand,
})

export const envSchema = z
  .array(envVarSchema)
  .max(32, 'Höchstens 32 Umgebungsvariablen')
  .refine((env) => new Set(env.map((e) => e.key.toUpperCase())).size === env.length, {
    message: 'Eine Umgebungsvariable ist doppelt',
  })

export const syncItems = ['options', 'servers', 'resourcePacks', 'commandHistory', 'hotbar'] as const

export const syncSettingsSchema = z.object({
  options: z.boolean(),
  servers: z.boolean(),
  resourcePacks: z.boolean(),
  commandHistory: z.boolean(),
  hotbar: z.boolean(),
})

export const updateInstanceSchema = z.object({
  name: z.string().trim().min(1, 'Bitte einen Namen eingeben').max(64, 'Maximal 64 Zeichen'),
  overrides: z.object({
    maxMemoryMb: z.number().int().min(512).max(131072).nullable(),
    javaPath: javaPathSchema.nullable(),
    jvmArgs: z.string().max(4096).regex(noControl, 'JVM-Argumente enthalten ungültige Zeichen').nullable(),
    resolution: resolutionSchema.nullable(),
    trsClient: z.boolean().nullable(),
    boost: z.boolean().nullable(),
    updateChannel: z.enum(['release', 'beta', 'alpha']).nullable(),
    fullscreen: z.boolean().nullable(),
    hooks: hooksSchema.nullable(),
    env: envSchema.nullable(),
    syncSeparate: z.array(z.enum(syncItems)).max(syncItems.length),
  }),
})

export const groupSchema = z
  .string()
  .trim()
  .max(32, 'Gruppennamen: höchstens 32 Zeichen')
  .regex(noControl, 'Gruppenname enthält ungültige Zeichen')

export const uiSettingsSchema = z.object({
  theme: z.enum(['dark', 'oled', 'light', 'system']),
  accent: z.enum(['redstone', 'lamp', 'emerald', 'lapis', 'amethyst']),
  advancedRendering: z.boolean(),
  animatedBackground: z.boolean(),
  worldsTab: z.boolean(),
  screenshotsTab: z.boolean(),
  historyTab: z.boolean(),
  sidebarRecent: z.boolean(),
  sidebarAccount: z.boolean(),
  hideRightSidebar: z.boolean(),
  compactLibrary: z.boolean(),
  showPlayTime: z.boolean(),
  language: z.literal('de'),
})

export const settingsSchema = z
  .object({
    minMemoryMb: z.number().int().min(128),
    maxMemoryMb: z.number().int().min(512).max(131072),
    javaPath: javaPathSchema.nullable(),
    jvmArgs: z.string().max(4096).regex(noControl, 'JVM-Argumente enthalten ungültige Zeichen'),
    resolution: resolutionSchema,
    concurrentDownloads: z.number().int().min(1).max(64),
    closeOnLaunch: z.boolean(),
    showSnapshots: z.boolean(),
    preferDedicatedGpu: z.boolean(),
    autoFirewall: z.boolean(),
    fullscreen: z.boolean(),
    hooks: hooksSchema,
    env: envSchema,
    sync: syncSettingsSchema,
    ui: uiSettingsSchema,
    allowLogUpload: z.boolean(),
    java: z.object({
      java8: javaPathSchema.nullable(),
      java17: javaPathSchema.nullable(),
      java21: javaPathSchema.nullable(),
      java25: javaPathSchema.nullable(),
    }),
  })
  .passthrough()
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

const searchSlug = z.string().min(1).max(40).regex(/^[a-z0-9+-]+$/)
const projectId = z.string().min(1).max(64).regex(/^[A-Za-z0-9_-]+$/)

/** Spiegelt `validate_search` im Kern (Whitelist für Sortierung, Arten, Loader, Grenzen). */
export const modrinthSearchSchema = z.object({
  query: z.string().max(100),
  kind: z.enum(['mod', 'resourcepack', 'shaderpack', 'datapack', 'modpack']),
  gameVersions: z.array(z.string().min(1).max(32).regex(/^[A-Za-z0-9._+\- ]+$/)).max(30),
  loaders: z.array(z.enum(['fabric', 'quilt', 'forge', 'neoforge'])).max(30),
  categories: z.array(searchSlug).max(30),
  categoryMatch: z.enum(['all', 'any']),
  excludeCategories: z.array(searchSlug).max(30),
  environments: z.array(z.enum(['client', 'server'])).max(2),
  excludeProjectIds: z.array(projectId).max(300),
  openSource: z.boolean(),
  index: z.enum(['relevance', 'downloads', 'follows', 'newest', 'updated']),
  offset: z.number().int().min(0).max(10_000),
  limit: z.number().int().min(1).max(100),
})

/** Name eines Skins in der eigenen Sammlung (Kern kürzt zusätzlich auf 48). */
export const skinNameSchema = z
  .string()
  .trim()
  .min(1, 'Bitte einen Namen eingeben')
  .max(48, 'Maximal 48 Zeichen')
  .regex(noControl, 'Name enthält ungültige Zeichen')

export const skinVariants = ['classic', 'slim'] as const

/** Spiegelt `validate_options` im Kern (modpack_export.rs). */
export const exportOptionsSchema = z.object({
  name: z.string().trim().min(1, 'Bitte einen Namen eingeben').max(64, 'Maximal 64 Zeichen'),
  version: z
    .string()
    .trim()
    .min(1, 'Bitte eine Version angeben')
    .max(32, 'Maximal 32 Zeichen')
    .regex(/^[A-Za-z0-9._+-]+$/, 'Nur Buchstaben, Ziffern und . - _ +'),
  summary: z
    .string()
    .trim()
    .max(512, 'Maximal 512 Zeichen')
    .regex(noControl, 'Beschreibung enthält ungültige Zeichen')
    .nullable(),
  include: z.array(z.string().min(1).max(120)).min(1, 'Bitte mindestens einen Ordner auswählen').max(100),
})

export function firstIssue(error: z.ZodError): string {
  return error.issues[0]?.message ?? 'Ungültige Eingabe'
}
