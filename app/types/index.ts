import type { Locale } from '../utils/i18n'

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
  /** FPS-Boost beim Start (JVM-Abstimmung); null = global */
  performanceTuning: boolean | null
  /** Welche Modrinth-Versionen Updates nehmen; null = stabil */
  updateChannel: UpdateChannel | null
  /** Vollbild beim Start; null = global */
  fullscreen: boolean | null
  /** Eigene Start-Hooks; null = globale */
  hooks: LaunchHooks | null
  /** Eigene Umgebungsvariablen; null = globale */
  env: EnvVar[] | null
  /** Diese Dinge bleiben in dieser Instanz separat. */
  syncSeparate: SyncItem[]
}

export type UpdateChannel = 'release' | 'beta' | 'alpha'

export interface EnvVar {
  key: string
  value: string
}

export interface LaunchHooks {
  preLaunch: string | null
  wrapper: string | null
  postExit: string | null
}

export type SyncItem = 'options' | 'servers' | 'resourcePacks' | 'commandHistory' | 'hotbar'

export interface SyncSettings {
  options: boolean
  servers: boolean
  resourcePacks: boolean
  commandHistory: boolean
  hotbar: boolean
}

export type Theme = 'dark' | 'oled' | 'light' | 'system'
export type Accent = 'redstone' | 'lamp' | 'emerald' | 'lapis' | 'amethyst'

export interface UiSettings {
  theme: Theme
  accent: Accent
  advancedRendering: boolean
  animatedBackground: boolean
  worldsTab: boolean
  screenshotsTab: boolean
  historyTab: boolean
  sidebarRecent: boolean
  sidebarAccount: boolean
  hideRightSidebar: boolean
  compactLibrary: boolean
  showPlayTime: boolean
  language: Locale
}

export interface JavaPaths {
  java8: string | null
  java17: string | null
  java21: string | null
  java25: string | null
}

export interface JavaInstall {
  path: string
  major: number
  version: string
  managed: boolean
}

export interface JavaCheck {
  major: number | null
  version: string | null
}

export interface StorageStats {
  instances: number
  libraries: number
  assets: number
  versions: number
  java: number
  shared: number
  unused: number
  unusedVersions: number
}

export interface VerifyReport {
  checked: number
  removed: number
}

export interface LoaderVersionInfo {
  version: string
  stable: boolean
}

export interface UploadResult {
  fileName: string
  kind: ContentKind | null
  error: string | null
  /** Derselbe Fehler übersetzbar (`userErrorText(errorInfo)`). */
  errorInfo?: CommandError
}

export type BulkAction = 'enable' | 'disable' | 'delete'

export interface BulkResult {
  changed: number
  failed: number
}

export type DropEvent = { type: 'enter' } | { type: 'leave' } | { type: 'drop'; token: number; names: string[] }

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
  /** Freigegebener Pfad des breiten Titelbilds (Banner) */
  bannerPath?: string | null
  /** Eigene Gruppe in der Bibliothek */
  group?: string | null
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
  /** FPS-Boost beim Start: abgestimmte GC-Flags, Xms = Xmx (nur ohne eigene JVM-Argumente) */
  performanceTuning: boolean
  /** Prozesspriorität „Höher als normal“ */
  highPriority: boolean
  autoFirewall: boolean
  fullscreen: boolean
  hooks: LaunchHooks
  env: EnvVar[]
  sync: SyncSettings
  ui: UiSettings
  allowLogUpload: boolean
  java: JavaPaths
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
  os: string
}

/** TRS Client (In-Game-Mod): mitgelieferte Version, `update` = neuere aus dem Update-Kanal. */
export interface ClientModStatus {
  bundled: string | null
  update: string | null
}

/** Fehler vom Kern (`UserError` in `crates/core/src/error.rs`). */
export interface CommandError {
  kind: string
  /** Deutsche Rückfall-Meldung. */
  message: string
  /** Übersetzungs-Code: `errors.<code>` in `app/locales/*.json`. */
  code?: string
  params?: Record<string, string>
  /** Fehlercode der TRS API (z. B. `cape_locked`). */
  apiCode?: string
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
  | {
      type: 'notice'
      instanceId: string
      /** Deutsche Rückfall-Meldung. */
      message: string
      /** Übersetzungs-Code (`errors.<code>`), z. B. `hooks.postExitExitCode`. */
      code?: string
      params?: Record<string, string>
    }

export interface Diagnosis {
  kind: 'corrupt_files' | 'out_of_memory' | 'wrong_java' | 'missing_dependency' | 'mod_conflict' | 'graphics_driver'
  message: string
  /** Übersetzungs-Code der Meldung (`errors.<code>`), z. B. `process.crashOutOfMemory`. */
  code?: string
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
  | 'files_added'
  | 'content_bulk'
  | 'hooks_changed'
  | 'group_changed'
  | 'renamed'

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

// --- Skins & Umhänge ------------------------------------------------------------

export type SkinVariant = 'classic' | 'slim'

export interface Cape {
  id: string
  name: string
  active: boolean
  /** Textur als Data-URL (kommt aus dem Kern). */
  texture: string | null
}

export interface SkinProfile {
  name: string
  uuid: string
  variant: SkinVariant
  /** Aktive Skin-Textur als Data-URL. */
  skin: string | null
  capes: Cape[]
}

export interface LibrarySkin {
  id: string
  name: string
  variant: SkinVariant
  addedAt: string
  /** Textur als Data-URL. */
  texture: string
}

/** Gewünschter Skin: aus der Sammlung, getragener mit anderem Modell oder Standard. */
export type SkinChange =
  | { kind: 'library'; id: string; variant: SkinVariant }
  | { kind: 'current'; variant: SkinVariant }
  | { kind: 'default' }

/** Unterschied zwischen Entwurf und Konto – was fehlt, bleibt unverändert. */
export interface SkinChanges {
  skin: SkinChange | null
  /** `{ id: null }` = keinen Umhang tragen. */
  cape: { id: string | null } | null
}

export type SkinSyncState = 'idle' | 'applying' | 'waiting' | 'done' | 'failed'

export interface SkinSyncStatus {
  /** Zählt bei jeder Änderung hoch. */
  version: number
  state: SkinSyncState
  account: string | null
  reason: 'rateLimited' | 'pacing' | 'network' | null
  /** Unix-Zeit (ms), wann es automatisch weitergeht. */
  retryAt: number | null
  message: string | null
  /** `message` übersetzbar (`userErrorText(errorInfo)`), nur bei `failed`. */
  errorInfo?: CommandError
  pendingSkin: boolean
  pendingCape: boolean
  /** Neuer Stand nach `done`/`failed` (fehlt, wenn er nicht geladen werden konnte). */
  profile: SkinProfile | null
}

// --- Neuigkeiten ------------------------------------------------------------------

export type NewsSource = 'patchNotes' | 'mojang' | 'modrinth' | 'launcher'

export interface NewsItem {
  id: string
  source: NewsSource
  title: string
  /** Reiner Text – im Kern schon von HTML befreit. */
  summary: string
  date: string | null
  tag: string | null
  /** `tag` übersetzbar, wenn der Text vom Launcher stammt (`errors.news.tag*`). */
  tagInfo?: TranslatableText
  /** Downloads (Modrinth) – für `errors.news.tagDownloads` mit `formatCount`. */
  downloads?: number
  imageUrl?: string
  link?: string
  /** Pfad für den vollen Patchnotes-Text. */
  contentPath?: string
}

export interface NewsFeed {
  items: NewsItem[]
  fetchedAt: string
  /** Daten kommen aus dem Cache, weil das Laden scheiterte. */
  stale: boolean
}

// --- Modpack-Export ----------------------------------------------------------------

export interface ExportEntry {
  name: string
  isDir: boolean
  size: number
  files: number
  recommended: boolean
}

export interface ExportOptions {
  name: string
  version: string
  summary: string | null
  include: string[]
}

export interface ExportProgress {
  phase: 'hashing' | 'lookup' | 'writing'
  percent: number
}

export interface ExportSummary {
  downloads: number
  overrides: number
  bytes: number
  fileName: string
}

// --- Screenshot-Galerie --------------------------------------------------------------

export interface GalleryShot {
  instanceId: string
  instanceName: string
  fileName: string
  size: number
  takenAt: string | null
}

// --- Hintergrund-Aufgaben ------------------------------------------------------------

/** Art einer Hintergrund-Aufgabe (Aufgaben-Panel, Verlauf im Kern). */
export type TaskKind =
  | 'modpack'
  | 'modpack-file'
  | 'content'
  | 'content-update'
  | 'performance-pack'
  | 'presets'
  | 'java'
  | 'import'
  | 'export'
  | 'create'
  | 'duplicate'
  | 'repair'
  | 'reinstall'
  | 'version-change'
  | 'launch'

/** Eintrag im Verlauf fertiger Aufgaben (`task-history.json`, neueste zuerst). */
export interface TaskRecord {
  id: string
  kind: TaskKind
  title: string
  outcome: 'done' | 'failed'
  finishedAt: string
  instanceId?: string
  iconUrl?: string
  detail?: string
  bytes?: number
  /** Übersetzbare Fassung von `title` (Schlüssel + Parameter). */
  titleRef?: TextRef
  /** Übersetzbare Fassung von `detail`, z. B. `errors.<code>`. */
  detailRef?: TextRef
}

export interface NewTaskRecord {
  kind: TaskKind
  title: string
  outcome: 'done' | 'failed'
  instanceId?: string | null
  iconUrl?: string | null
  detail?: string | null
  bytes?: number | null
  titleRef?: TextRef | null
  detailRef?: TextRef | null
}

/** Verweis auf einen Oberflächentext: Schlüssel aus `app/locales/*.json` + Parameter. */
export interface TextRef {
  key: string
  params?: Record<string, string | number>
}

/** Übersetzbarer Text aus dem Kern (wie `Msg`): `errors.<code>`, sonst `message`. */
export interface TranslatableText {
  code: string
  params?: Record<string, string>
  message: string
}

/** Event `task-progress`: Byte-Stand einer laufenden Aufgabe. */
export interface TaskProgressEvent {
  taskId: string
  doneBytes: number
  totalBytes: number
  paused: boolean
}

// --- Mod-Presets -------------------------------------------------------------

/** Woher ein Preset-Eintrag stammt (später auch CurseForge). */
export type PresetSource = 'modrinth'

export interface PresetItem {
  source: PresetSource
  projectId: string
  title: string
  iconUrl: string | null
  kind: ContentKind
}

/** Fertige TRS-Presets (Name/Beschreibung: `presets.builtin.<key>`). */
export type BuiltinPreset = 'fpsBoost' | 'nvidium' | 'voiceChat' | 'replay'

export interface Preset {
  id: string
  /** Eigener Name; leer bei fertigen Presets. */
  name: string
  builtin: BuiltinPreset | null
  /** Bei jeder neuen Instanz vorausgewählt. */
  auto: boolean
  /** Wird bei Modpacks mit angeboten. */
  modpackSafe: boolean
  /** Passt zu diesem PC (Nvidium nur mit passender NVIDIA-Karte). */
  available: boolean
  items: PresetItem[]
}

export interface PresetInput {
  name: string
  auto: boolean
  items: PresetItem[]
}

export type PresetItemStatus =
  | 'installed'
  | 'alreadyInstalled'
  | 'duplicate'
  | 'notAvailable'
  | 'missingDependency'
  | 'incompatible'
  | 'needsLoader'
  | 'failed'

export interface PresetItemOutcome {
  presetId: string
  projectId: string | null
  title: string
  iconUrl: string | null
  kind: ContentKind
  status: PresetItemStatus
  /** Teil einer Sammlung (FPS-Boost) – „nicht verfügbar“ ist dann normal. */
  optional: boolean
  versionNumber: string | null
  /** Fehlende Abhängigkeit bzw. womit es sich nicht verträgt. */
  detail: string | null
  error: CommandError | null
}

export interface PresetApplyReport {
  gameVersion: string
  loader: LoaderKind
  items: PresetItemOutcome[]
  files: string[]
  dependencies: number
}

export interface PresetProgress {
  phase: 'resolve' | 'install'
  done: number
  total: number
  title: string | null
}
