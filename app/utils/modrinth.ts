import type { ContentKind, Instance, LoaderKind, ModrinthVersion, ProjectKind } from '~/types'

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

export const versionTypeLabels: Record<string, string> = { release: 'Stabil', beta: 'Beta', alpha: 'Alpha' }

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

/** Modrinth-Kategorien auf Deutsch (unbekannte bleiben, wie sie sind). */
export const categoryLabels: Record<string, string> = {
  adventure: 'Abenteuer',
  cursed: 'Verflucht',
  decoration: 'Deko',
  economy: 'Wirtschaft',
  equipment: 'Ausrüstung',
  food: 'Essen',
  'game-mechanics': 'Spielmechanik',
  library: 'Bibliothek',
  magic: 'Magie',
  management: 'Verwaltung',
  minigame: 'Minispiel',
  mobs: 'Kreaturen',
  optimization: 'Optimierung',
  social: 'Sozial',
  storage: 'Lager',
  technology: 'Technik',
  transportation: 'Transport',
  utility: 'Werkzeuge',
  worldgen: 'Weltgenerierung',
  combat: 'Kampf',
  challenging: 'Herausfordernd',
  'kitchen-sink': 'Alles drin',
  lightweight: 'Leichtgewichtig',
  multiplayer: 'Mehrspieler',
  quests: 'Quests',
  'audio': 'Audio',
  blocks: 'Blöcke',
  entities: 'Entities',
  environment: 'Umgebung',
  fonts: 'Schriften',
  gui: 'Oberfläche',
  items: 'Items',
  models: 'Modelle',
  simplistic: 'Schlicht',
  themed: 'Thematisch',
  tweaks: 'Anpassungen',
  realistic: 'Realistisch',
  'semi-realistic': 'Halb-realistisch',
  'vanilla-like': 'Vanilla-nah',
  atmosphere: 'Atmosphäre',
  bloom: 'Bloom',
  shadows: 'Schatten',
  reflections: 'Spiegelungen',
  fantasy: 'Fantasy',
  cartoon: 'Cartoon',
  potato: 'Für schwache PCs',
  low: 'Niedrig',
  medium: 'Mittel',
  high: 'Hoch',
  screenshot: 'Screenshot',
  pbr: 'PBR',
  'colored-lighting': 'Farbiges Licht',
  'path-tracing': 'Path Tracing',
  foliage: 'Pflanzen',
  'core-shaders': 'Core Shader',
}
