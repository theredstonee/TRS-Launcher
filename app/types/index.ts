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
  | { type: 'exited'; instanceId: string; exitCode: number | null; crashed: boolean; playSeconds: number }

export type ContentKind = 'mod' | 'resourcepack' | 'shaderpack'

export interface ContentItem {
  fileName: string
  kind: ContentKind
  enabled: boolean
  size: number
  title: string | null
  version: string | null
  description: string | null
  source: { projectId: string; versionId: string } | null
}

export type ProjectKind = ContentKind | 'modpack'

export interface ModrinthSearchParams {
  query: string
  kind: ProjectKind
  gameVersion: string | null
  loader: LoaderKind | null
  offset: number
}

export interface ModrinthHit {
  projectId: string
  slug: string
  title: string
  description: string
  author: string
  iconUrl: string | null
  downloads: number
  categories: string[]
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
