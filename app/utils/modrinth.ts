import type { ContentKind, Instance, LoaderKind, ModrinthVersion, ProjectKind } from '~/types'
// Relativ importiert, damit Tests die Helfer ohne Nuxt laden können.
import { hasKey, t, tKey } from './i18n'

/** Loader-Namen, mit denen Modrinth passende Mods kennzeichnet (Quilt lädt auch Fabric-Mods). */
export const loaderTags: Record<LoaderKind, string[]> = {
  vanilla: [],
  fabric: ['fabric'],
  quilt: ['quilt', 'fabric'],
  forge: ['forge'],
  neoforge: ['neoforge'],
}

/** Modrinth-Projekttyp → unsere Inhaltsart. */
export function projectKindOf(projectType: string): ProjectKind {
  if (projectType === 'modpack') return 'modpack'
  if (projectType === 'resourcepack') return 'resourcepack'
  if (projectType === 'shader') return 'shaderpack'
  if (projectType === 'datapack') return 'datapack'
  return 'mod'
}

/** Passt die Version zu Minecraft-Version und Modloader der Instanz? */
export function versionFits(v: ModrinthVersion, instance: Pick<Instance, 'gameVersion' | 'loader'>, kind: ContentKind): boolean {
  if (!v.gameVersions.includes(instance.gameVersion)) return false
  // Datenpakete gibt es oft auch als Mod-Variante – nur die reine Datapack-Datei passt.
  if (kind === 'datapack') return v.loaders.includes('datapack')
  if (kind !== 'mod') return true
  const tags = loaderTags[instance.loader.kind]
  return v.loaders.some((l) => tags.includes(l))
}

/** „Stabil“ / „Beta“ / „Alpha“; unbekannte Typen bleiben, wie sie sind. */
export function versionTypeLabel(type: string): string {
  return type === 'release' || type === 'beta' || type === 'alpha' ? t(`modrinth.versionType.${type}`) : type
}

/**
 * Wie `versionTypeLabel`, als Objekt für bestehende Aufrufer
 * (`versionTypeLabels[type] ?? type`). Getter, damit der Text der Sprache folgt.
 */
export const versionTypeLabels: Record<string, string> = {
  get release() {
    return t('modrinth.versionType.release')
  },
  get beta() {
    return t('modrinth.versionType.beta')
  },
  get alpha() {
    return t('modrinth.versionType.alpha')
  },
}

/** Anzeige der Seite (client_side/server_side) eines Projekts. */
export function sideLabel(side: string): string {
  return side === 'required' || side === 'optional' || side === 'unsupported' || side === 'unknown'
    ? t(`modrinth.side.${side}`)
    : side
}

export const loaderNames: Record<string, string> = {
  fabric: 'Fabric',
  quilt: 'Quilt',
  forge: 'Forge',
  neoforge: 'NeoForge',
  minecraft: 'Minecraft',
  iris: 'Iris',
  optifine: 'OptiFine',
  canvas: 'Canvas',
  vanilla: 'Vanilla',
  datapack: 'Datapack',
}

/** Kompakte Anzeige der Spielversionen, z. B. „1.21 – 1.21.4“. */
export function gameVersionRange(versions: string[]): string {
  if (!versions.length) return ''
  if (versions.length === 1) return versions[0]!
  return `${versions[0]} – ${versions[versions.length - 1]}`
}

/**
 * Anzeigename einer Modrinth-Kategorie (`game-mechanics` → „Spielmechanik“).
 * Unbekannte Tags erscheinen mit großem Anfangsbuchstaben und Leerzeichen.
 */
export function categoryLabel(name: string): string {
  if (/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name)) {
    const key = `modrinth.categories.${name.replace(/-([a-z0-9])/g, (_, c: string) => c.toUpperCase())}`
    if (hasKey(key)) return tKey(key)
  }
  return name.charAt(0).toUpperCase() + name.slice(1).replaceAll('-', ' ')
}
