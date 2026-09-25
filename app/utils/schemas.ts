import { z } from 'zod'
import { supportedLocales, t, type MessageKey, type NamedParams } from './i18n'
import { motionModes } from './motion'

// Spiegelt die Regeln aus `trs_core` – der Kern validiert trotzdem immer selbst.

/**
 * Fehlermeldung, die zod erst beim Prüfen übersetzt – folgt so der aktuell
 * eingestellten Sprache.
 */
function msg(key: MessageKey, params?: NamedParams) {
  return { error: () => t(key, params) }
}

const versionString = z
  .string()
  .min(1)
  .max(64)
  .regex(/^[A-Za-z0-9._+\- ]+$/, msg('validation.invalidCharacters'))

export const loaderKinds = ['vanilla', 'fabric', 'quilt', 'forge', 'neoforge'] as const

export const newInstanceSchema = z.object({
  name: z.string().trim().min(1, msg('validation.nameRequired')).max(64, msg('validation.maxChars', { max: 64 })),
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
  .regex(noControl, msg('validation.javaPathInvalid'))
  // Windows: `C:\…\javaw.exe`, Linux: `/…/bin/java` – der Kern prüft zusätzlich je System.
  .regex(/^(?:[A-Za-z]:\\.*\\javaw?\.exe|\/.*\/java)$/i, msg('validation.javaPathNotJava'))

const hookCommand = z
  .string()
  .trim()
  .max(1024, msg('validation.commandTooLong', { max: 1024 }))
  .regex(noControl, msg('validation.commandInvalidChars'))
  .nullable()
  .transform((v) => (v ? v : null))

export const envVarSchema = z.object({
  key: z
    .string()
    .trim()
    .max(64, msg('validation.envKeyTooLong', { max: 64 }))
    .regex(/^[A-Za-z_][A-Za-z0-9_]*$/, msg('validation.envKeyFormat')),
  value: z
    .string()
    .max(1024, msg('validation.envValueTooLong', { max: 1024 }))
    .regex(noControl, msg('validation.envValueInvalidChars')),
})

export const hooksSchema = z.object({
  preLaunch: hookCommand,
  wrapper: hookCommand,
  postExit: hookCommand,
})

export const envSchema = z
  .array(envVarSchema)
  .max(32, msg('validation.envTooMany', { max: 32 }))
  .refine((env) => new Set(env.map((e) => e.key.toUpperCase())).size === env.length, msg('validation.envDuplicate'))

export const syncItems = ['options', 'servers', 'resourcePacks', 'commandHistory', 'hotbar'] as const

export const syncSettingsSchema = z.object({
  options: z.boolean(),
  servers: z.boolean(),
  resourcePacks: z.boolean(),
  commandHistory: z.boolean(),
  hotbar: z.boolean(),
})

export const updateInstanceSchema = z.object({
  name: z.string().trim().min(1, msg('validation.nameRequired')).max(64, msg('validation.maxChars', { max: 64 })),
  overrides: z.object({
    maxMemoryMb: z.number().int().min(512).max(131072).nullable(),
    javaPath: javaPathSchema.nullable(),
    jvmArgs: z.string().max(4096).regex(noControl, msg('validation.jvmArgsInvalidChars')).nullable(),
    resolution: resolutionSchema.nullable(),
    trsClient: z.boolean().nullable(),
    boost: z.boolean().nullable(),
    performanceTuning: z.boolean().nullable().default(null),
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
  .max(32, msg('validation.groupTooLong', { max: 32 }))
  .regex(noControl, msg('validation.groupInvalidChars'))

export const uiSettingsSchema = z.object({
  theme: z.enum(['dark', 'oled', 'light', 'system']),
  accent: z.enum(['redstone', 'lamp', 'emerald', 'lapis', 'amethyst']),
  advancedRendering: z.boolean(),
  animatedBackground: z.boolean(),
  motion: z.enum(motionModes),
  worldsTab: z.boolean(),
  screenshotsTab: z.boolean(),
  historyTab: z.boolean(),
  sidebarRecent: z.boolean(),
  sidebarAccount: z.boolean(),
  hideRightSidebar: z.boolean(),
  compactLibrary: z.boolean(),
  showPlayTime: z.boolean(),
  language: z.enum(supportedLocales),
})

/** Clips & Aufnahme – gleiche Grenzen wie `trs_core::clips::settings`. */
export const clipSettingsSchema = z.object({
  enabled: z.boolean(),
  bufferSeconds: z
    .number()
    .int()
    .min(15, msg('validation.clipBufferRange', { min: 15, max: 120 }))
    .max(120, msg('validation.clipBufferRange', { min: 15, max: 120 })),
  resolution: z.enum(['native', '1080p', '720p']),
  fps: z.union([z.literal(30), z.literal(60)]),
  quality: z.enum(['low', 'medium', 'high']),
  encoder: z.enum(['auto', 'nvenc', 'amf', 'qsv', 'x264']),
  systemAudio: z.boolean(),
  microphone: z.boolean(),
  folder: z.string().max(400).regex(noControl, msg('validation.invalidCharacters')).nullable(),
  maxStorageGb: z
    .number()
    .int()
    .min(1, msg('validation.clipStorageRange', { min: 1, max: 2000 }))
    .max(2000, msg('validation.clipStorageRange', { min: 1, max: 2000 })),
})

export const settingsSchema = z
  .object({
    minMemoryMb: z.number().int().min(128),
    maxMemoryMb: z.number().int().min(512).max(131072),
    javaPath: javaPathSchema.nullable(),
    jvmArgs: z.string().max(4096).regex(noControl, msg('validation.jvmArgsInvalidChars')),
    resolution: resolutionSchema,
    concurrentDownloads: z.number().int().min(1).max(64),
    closeOnLaunch: z.boolean(),
    showSnapshots: z.boolean(),
    preferDedicatedGpu: z.boolean(),
    performanceTuning: z.boolean().default(true),
    highPriority: z.boolean().default(false),
    autoFirewall: z.boolean(),
    fullscreen: z.boolean(),
    hooks: hooksSchema,
    env: envSchema,
    sync: syncSettingsSchema,
    ui: uiSettingsSchema,
    allowLogUpload: z.boolean(),
    discordPresence: z.boolean().default(true),
    java: z.object({
      java8: javaPathSchema.nullable(),
      java17: javaPathSchema.nullable(),
      java21: javaPathSchema.nullable(),
      java25: javaPathSchema.nullable(),
    }),
    clips: clipSettingsSchema,
    trsSync: z.boolean().default(true),
  })
  .passthrough()
  .refine((s) => s.minMemoryMb <= s.maxMemoryMb, {
    ...msg('validation.minAboveMax'),
    path: ['minMemoryMb'],
  })

export const serverSchema = z.object({
  name: z.string().trim().min(1, msg('validation.nameRequired')).max(64, msg('validation.maxChars', { max: 64 })),
  address: z
    .string()
    .trim()
    .min(1, msg('validation.addressRequired'))
    .max(260)
    .regex(/^[A-Za-z0-9._-]+(:\d{1,5})?$/, msg('validation.addressFormat')),
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
  .min(1, msg('validation.nameRequired'))
  .max(48, msg('validation.maxChars', { max: 48 }))
  .regex(noControl, msg('validation.nameInvalidChars'))

export const skinVariants = ['classic', 'slim'] as const

/** Link zu einem Skin-PNG: nur https, ohne Zugangsdaten (Kern prüft zusätzlich Host/IP). */
export const skinUrlSchema = z
  .string()
  .trim()
  .min(1, msg('validation.urlRequired'))
  .max(2048, msg('validation.maxChars', { max: 2048 }))
  .regex(noControl, msg('validation.invalidCharacters'))
  .refine((value) => {
    try {
      const url = new URL(value)
      return url.protocol === 'https:' && !url.username && !url.password && !!url.hostname
    } catch {
      return false
    }
  }, msg('validation.urlHttps'))

/** Spiegelt `validate_options` im Kern (modpack_export.rs). */
export const exportOptionsSchema = z.object({
  name: z.string().trim().min(1, msg('validation.nameRequired')).max(64, msg('validation.maxChars', { max: 64 })),
  version: z
    .string()
    .trim()
    .min(1, msg('validation.versionRequired'))
    .max(32, msg('validation.maxChars', { max: 32 }))
    .regex(/^[A-Za-z0-9._+-]+$/, msg('validation.versionFormat')),
  summary: z
    .string()
    .trim()
    .max(512, msg('validation.maxChars', { max: 512 }))
    .regex(noControl, msg('validation.descriptionInvalidChars'))
    .nullable(),
  include: z.array(z.string().min(1).max(120)).min(1, msg('validation.folderRequired')).max(100),
})

export function firstIssue(error: z.ZodError): string {
  return error.issues[0]?.message ?? t('validation.invalidInput')
}

// --- Mod-Presets (Regeln wie in `trs_core::presets`) ---------------------------

export const PRESET_NAME_MAX = 48
export const PRESET_ITEMS_MAX = 100

export const presetItemSchema = z.object({
  source: z.literal('modrinth'),
  projectId: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  title: z.string().trim().min(1).max(100),
  iconUrl: z
    .string()
    .max(512)
    .refine((u) => u.startsWith('https://cdn.modrinth.com/'))
    .nullable(),
  kind: z.enum(['mod', 'resourcepack', 'shaderpack', 'datapack']),
})

export const presetInputSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, msg('validation.nameRequired'))
    .max(PRESET_NAME_MAX, msg('validation.maxChars', { max: PRESET_NAME_MAX }))
    .regex(noControl, msg('validation.invalidCharacters')),
  auto: z.boolean(),
  items: z
    .array(presetItemSchema)
    .max(PRESET_ITEMS_MAX, msg('presets.editor.tooManyItems', { max: PRESET_ITEMS_MAX }))
    .refine((items) => new Set(items.map((i) => i.projectId)).size === items.length, msg('presets.editor.duplicate')),
})
