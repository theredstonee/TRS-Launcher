import type { CategoryTag, ContentKind, ContentSource, Platform, ProjectKind, SortIndex } from '~/types'
// Relativ importiert, damit Tests die Helfer ohne Nuxt laden können.
import { backend } from './backend'

// Zwei Quellen, eine Oberfläche: Modrinth und CurseForge liefern (im Kern
// umgewandelt) dieselben Datenformen. Hier sitzt, was sich unterscheidet.

export const platforms: Platform[] = ['modrinth', 'curseforge']

export function isPlatform(value: unknown): value is Platform {
  return value === 'modrinth' || value === 'curseforge'
}

/** Plattform einer installierten Datei (alte Einträge ohne Angabe = Modrinth). */
export function sourcePlatform(source: Pick<ContentSource, 'platform'> | null | undefined): Platform {
  return source?.platform === 'curseforge' ? 'curseforge' : 'modrinth'
}

/**
 * Eindeutiger Schlüssel über beide Plattformen – so meldet der Kern
 * installierte Projekte (`installedProjects`): Modrinth-IDs ohne,
 * CurseForge-IDs mit Präfix `cf:`.
 */
export function projectKey(platform: Platform, projectId: string): string {
  return platform === 'curseforge' ? `cf:${projectId}` : projectId
}

/** Link zur Projektseite im Launcher. */
export function projectRoute(platform: Platform, projectId: string, instanceId?: string | null) {
  const query: Record<string, string> = {}
  if (platform === 'curseforge') query.platform = 'curseforge'
  if (instanceId) query.instance = instanceId
  return { path: `/project/${projectId}`, query }
}

/** Sortierungen je Quelle – CurseForge kennt keine „Follower“. */
export function sortIndexesFor(platform: Platform): SortIndex[] {
  return platform === 'curseforge'
    ? ['relevance', 'downloads', 'newest', 'updated']
    : ['relevance', 'downloads', 'follows', 'newest', 'updated']
}

/** CurseForge liefert höchstens 50 Treffer je Seite. */
export function pageSizesFor(platform: Platform): number[] {
  return platform === 'curseforge' ? [20, 50] : [20, 50, 100]
}

/** Projekttyp, unter dem die Kategorien einer Art geführt werden. */
export function categoryTypeFor(platform: Platform, kind: ProjectKind): string {
  if (kind === 'shaderpack') return 'shader'
  // Modrinth-Datenpakete nutzen die Mod-Kategorien, CurseForge hat eigene.
  if (kind === 'datapack') return platform === 'curseforge' ? 'datapack' : 'mod'
  return kind
}

/** Anzeigenamen der CurseForge-Kategorien (ID → Name). */
export function categoryLabels(tags: CategoryTag[]): Map<string, string> {
  return new Map(tags.filter((c) => c.label).map((c) => [c.name, c.label!]))
}

/** Nur Bilder von CurseForges Bild-CDN (wie im Kern). */
export function isCurseForgeImage(url: string | null | undefined): url is string {
  return !!url && url.startsWith('https://media.forgecdn.net/') && url.length > 'https://media.forgecdn.net/'.length && !/[\s"'<>\\@]/.test(url)
}

/** Dieselben Abfragen für beide Quellen. */
export const platformApi = {
  project: (platform: Platform, projectId: string) =>
    platform === 'curseforge' ? backend.curseforge.project(projectId) : backend.modrinthProject(projectId),
  projectVersions: (platform: Platform, projectId: string) =>
    platform === 'curseforge' ? backend.curseforge.projectVersions(projectId) : backend.modrinthProjectVersions(projectId),
  projects: (platform: Platform, ids: string[]) =>
    platform === 'curseforge' ? backend.curseforge.projects(ids) : backend.modrinthProjects(ids),
  versions: (platform: Platform, instanceId: string, projectId: string, kind: ContentKind) =>
    platform === 'curseforge'
      ? backend.curseforge.versions(instanceId, projectId, kind)
      : backend.modrinthVersions(instanceId, projectId, kind),
}
