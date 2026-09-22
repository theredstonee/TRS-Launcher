// Spiegelt die serde-Typen aus `src-tauri/crates/core`.

export type LoaderKind = 'vanilla' | 'fabric' | 'quilt' | 'forge' | 'neoforge'

export interface Loader {
  kind: LoaderKind
  version: string | null
}

export interface Resolution {
  width: number
  height: number
}

export interface InstanceOverrides {
  maxMemoryMb: number | null
  javaPath: string | null
  jvmArgs: string | null
  resolution: Resolution | null
  /** TRS Client in dieser Instanz; null = an */
  trsClient: boolean | null
  /** TRS-Optimierung für Vanilla (Fabric + Performance-Mods); null = an */
  boost: boolean | null
}

export interface Instance {
  id: string
  name: string
  gameVersion: string
  loader: Loader
  createdAt: string
  lastPlayed: string | null
  totalPlaySeconds: number
  overrides: InstanceOverrides
  /** Dateiname des Instanz-Bilds */
  icon?: string | null
  /** Freigegebener Pfad fürs Webview (convertFileSrc) */
  iconPath?: string | null
}

export interface NewInstance {
  name: string
  gameVersion: string
  loader: Loader
}

export interface Settings {
  minMemoryMb: number
  maxMemoryMb: number
  javaPath: string | null
  jvmArgs: string
  resolution: Resolution
  concurrentDownloads: number
  closeOnLaunch: boolean
  showSnapshots: boolean
  preferDedicatedGpu: boolean
  autoFirewall: boolean
}

export type VersionType = 'release' | 'snapshot' | 'old_beta' | 'old_alpha'

export interface ManifestVersion {
  id: string
  type: VersionType
  url: string
  releaseTime: string
  sha1: string
}

export interface VersionManifest {
  latest: { release: string; snapshot: string }
  versions: ManifestVersion[]
}

export interface AppInfo {
  version: string
  dataDir: string
}

export interface CommandError {
  kind: string
  message: string
}

export interface Account {
  id: string
  name: string
  skinUrl: string | null
  active: boolean
  addedAt: string
}

export interface DeviceCode {
  userCode: string
  verificationUri: string
  expiresIn: number
}

export type LaunchStage = 'version' | 'java' | 'loader' | 'libraries' | 'assets' | 'starting'

export interface StageProgress {
  stage: LaunchStage
  /** 0–100 innerhalb der Stufe */
  percent: number
  doneFiles: number
  totalFiles: number
}

export type LogLevel = 'trace' | 'debug' | 'info' | 'warn' | 'error' | 'fatal'

export interface LogLine {
  time: number
  level: LogLevel
  thread: string | null
  message: string
}

export interface RunningGame {
  instanceId: string
  pid: number
  startedAt: string
}

export type GameEvent =
  | { type: 'started'; instanceId: string; pid: number }
  | { type: 'logs'; instanceId: string; lines: LogLine[] }
  | {
      type: 'exited'
      instanceId: string
      exitCode: number | null
      crashed: boolean
      playSeconds: number
      diagnosis: Diagnosis | null
    }

export interface Diagnosis {
  kind: 'corrupt_files' | 'out_of_memory' | 'wrong_java' | 'missing_dependency' | 'mod_conflict' | 'graphics_driver'
  message: string
  canRepair: boolean
}

export type ContentKind = 'mod' | 'resourcepack' | 'shaderpack' | 'datapack'

export interface ContentItem {
  fileName: string
  kind: ContentKind
  enabled: boolean
  size: number
  title: string | null
  version: string | null
  description: string | null
  source: { projectId: string; versionId: string; versionNumber?: string } | null
  author: string | null
  /** Modrinth-CDN-URL oder Data-URL aus der Datei */
  iconUrl: string | null
  slug: string | null
}

export type ProjectKind = ContentKind | 'modpack'

/** Modrinths Such-Indizes. */
export type SortIndex = 'relevance' | 'downloads' | 'follows' | 'newest' | 'updated'
export type SearchEnvironment = 'client' | 'server'

export interface ModrinthSearchParams {
  query: string
  kind: ProjectKind
  /** ODER-verknüpft */
  gameVersions: string[]
  /** Modrinth-Loadernamen (fabric, quilt, forge, neoforge), ODER-verknüpft */
  loaders: string[]
  categories: string[]
  /** all = alle Kategorien nötig, any = eine reicht */
  categoryMatch: 'all' | 'any'
  excludeCategories: string[]
  environments: SearchEnvironment[]
  /** z. B. bereits installierte Projekte ausblenden (max. 300) */
  excludeProjectIds: string[]
  openSource: boolean
  index: SortIndex
  offset: number
  /** 1–100 */
  limit: number
}

/** Kategorie aus Modrinths Tag-API. icon ist SVG-Markup – nur als <img>-Data-URL anzeigen. */
export interface CategoryTag {
  name: string
  projectType: string
  header: string
  icon: string | null
}

export interface ModrinthHit {
  projectId: string
  slug: string
  title: string
  description: string
  author: string
  iconUrl: string | null
  downloads: number
  follows: number
  categories: string[]
  clientSide: 'required' | 'optional' | 'unsupported' | 'unknown'
  serverSide: 'required' | 'optional' | 'unsupported' | 'unknown'
  dateModified: string | null
  license: string | null
}

export interface ModrinthSearchResult {
  hits: ModrinthHit[]
  totalHits: number
  offset: number
  limit: number
}

export interface ModrinthVersion {
  id: string
  name: string
  versionNumber: string
  versionType: string
  datePublished: string | null
  gameVersions: string[]
  loaders: string[]
  fileName: string
  size: number
  /** Markdown – nur über renderMarkdown anzeigen */
  changelog: string | null
  dependencies: DependencyInfo[]
}

export interface ContentUpdate {
  kind: ContentKind
  fileName: string
  projectId: string
  versionId: string
  versionNumber: string
}

export interface PackProgress {
  phase: 'pack' | 'files' | 'overrides'
  percent: number
}

export interface Server {
  id: string
  name: string
  address: string
  autoResourcePack: boolean
}

export interface ServerInput {
  name: string
  address: string
  autoResourcePack: boolean
}

export interface ServerStatus {
  online: boolean
  playersOnline: number
  playersMax: number
  motd: string
  version: string
  favicon: string | null
  latencyMs: number
}

/** Screenshot oder Welt; `path` ist fürs Webview freigegeben (convertFileSrc). */
export interface ImageEntry {
  name: string
  path: string | null
  size: number
  date: string | null
}

export type ImportSource = 'vanilla' | 'prism' | 'multimc' | 'curseforge' | 'modrinth' | 'folder'

export interface ImportCandidate {
  id: string
  source: ImportSource
  name: string
  gameVersion: string
  loader: Loader
  modCount: number
  worldCount: number
  versionGuessed: boolean
}

export interface ImportProgress {
  percent: number
  doneFiles: number
  totalFiles: number
}

export type HistoryKind =
  | 'created'
  | 'imported'
  | 'launched'
  | 'stopped'
  | 'crashed'
  | 'mod_installed'
  | 'mod_updated'
  | 'mod_removed'
  | 'mod_enabled'
  | 'mod_disabled'
  | 'version_switched'
  | 'repaired'
  | 'icon_changed'

export interface HistoryEntry {
  at: string
  kind: HistoryKind
  subject?: string
  from?: string
  to?: string
  detail?: string
  seconds?: number
}

export interface DependencyInfo {
  projectId: string | null
  versionId: string | null
  dependencyType: string
}

export interface ProjectCard {
  projectId: string
  slug: string
  projectType: string
  title: string
  description: string
  author: string | null
  iconUrl: string | null
  downloads: number
}

export interface GalleryImage {
  url: string
  title: string | null
  description: string | null
  featured: boolean
}

export interface ProjectLink {
  kind: 'source' | 'issues' | 'wiki' | 'discord' | 'modrinth'
  url: string
}

export interface ProjectDetails {
  projectId: string
  slug: string
  projectType: string
  title: string
  description: string
  /** Markdown – nur über `renderMarkdown` anzeigen. */
  body: string
  author: string | null
  iconUrl: string | null
  downloads: number
  followers: number
  categories: string[]
  loaders: string[]
  gameVersions: string[]
  gallery: GalleryImage[]
  updated: string | null
  published: string | null
  license: string | null
  clientSide: string
  serverSide: string
  links: ProjectLink[]
}

export interface MigrationItem {
  kind: ContentKind
  fileName: string
  title: string
  iconUrl: string | null
  projectId: string
  currentVersion: string | null
  status: 'compatible' | 'update' | 'missing'
  targetVersionId: string | null
  targetVersionNumber: string | null
}
