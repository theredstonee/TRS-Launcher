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
  /** Ordner, die diese Instanz per Link mit anderen teilt (nur über `setInstanceSharedFolder` änderbar). */
  sharedFolders: SharedFolder[]
  /** Touch-Layout der mobilen App; null/fehlt = Standard (PvP) */
  touchProfile?: string | null
}

/** Gemeinsame Ordner zwischen Instanzen (Rust: `shared_folders::SharedFolder`). */
export type SharedFolder = 'shaderpacks' | 'resourcepacks' | 'screenshots' | 'saves' | 'schematics'

/** Zustand eines gemeinsamen Ordners in einer Instanz. */
export interface SharedFolderStatus {
  kind: SharedFolder
  /** In dieser Instanz eingeschaltet. */
  enabled: boolean
  /** Der Link steht (sonst: wird beim nächsten Start eingerichtet). */
  linked: boolean
  /** Ein fremder Link oder eine Datei steht im Weg. */
  blocked: boolean
  /** So viele Instanzen teilen diesen Ordner (diese mitgezählt). */
  instances: number
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
/** Animationen: immer, wie im System („Bewegung reduzieren“) oder reduziert. */
export type Motion = 'full' | 'system' | 'reduced'

export interface UiSettings {
  theme: Theme
  accent: Accent
  advancedRendering: boolean
  animatedBackground: boolean
  motion: Motion
  worldsTab: boolean
  screenshotsTab: boolean
  historyTab: boolean
  sidebarRecent: boolean
  sidebarAccount: boolean
  hideRightSidebar: boolean
  compactLibrary: boolean
  showPlayTime: boolean
  language: Locale
  /** Beim Start gleich im Big-Picture-Modus öffnen. */
  bigPictureOnStart: boolean
  /** Auf Steam Deck/SteamOS/gamescope von selbst im Big-Picture-Modus starten (ab Werk an). */
  bigPictureAuto: boolean
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
  /** Gemeinsame Ordner – einmal gezählt, egal wie viele Instanzen sie nutzen. */
  sharedFolders: number
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
  /** Discord-Status „Spielt TRS Launcher“ (Version, Loader, Spielzeit) – ab Werk an */
  discordPresence: boolean
  java: JavaPaths
  clips: ClipSettings
  /** Eigene Skins, eigene Presets, Theme, Akzentfarbe und Sprache mit dem TRS-Konto abgleichen – ab Werk an */
  trsSync: boolean
  /** Benachrichtigungen aus „Sozial“ (nur lokal) */
  social: SocialSettings
  /** TRS Client bei Modpacks/Importen mit Mods: fragen (Standard) oder immer mit/ohne */
  modpackTrsClient: ModpackTrsPolicy
  /** „Minecraft Bedrock“ in der Bibliothek zeigen, wenn installiert (nur Windows) – ab Werk an */
  showBedrock: boolean
  /** Ordner, die neue Instanzen von Anfang an teilen */
  sharedFolders: SharedFolder[]
  /** PC-Fernbedienung vom Handy (ab Werk aus) */
  remote: RemoteSettings
}

/** PC-Fernbedienung (Rust: `RemoteSettings`): an/aus und je Befehlsart erlaubt. */
export interface RemoteSettings {
  enabled: boolean
  /** Spiele starten und beenden */
  allowLaunch: boolean
  /** Modpacks per Code installieren */
  allowInstall: boolean
}

/** Benachrichtigungen aus „Sozial“ (Rust: `SocialSettings`). */
export interface SocialSettings {
  toasts: boolean
  corner: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left'
  durationSecs: number
  sound: boolean
  doNotDisturb: boolean
  quietInFullscreen: boolean
  native: boolean
  quickReply: boolean
  messages: boolean
  invites: boolean
  friendRequests: boolean
  capeOffers: boolean
  friendOnline: boolean
  /** Issues auf der Website, denen man folgt (§28). */
  issues: boolean
  /** Freigeschaltete Erfolge. */
  achievements: boolean
}

// --- Clips & Aufnahme ------------------------------------------------------------------

export type ClipResolution = 'native' | '1080p' | '720p'
export type ClipQuality = 'low' | 'medium' | 'high'
export type ClipEncoder = 'auto' | 'nvenc' | 'amf' | 'qsv' | 'x264'
/** Tatsächlich verwendeter Encoder (mf = Media Foundation). */
export type ClipCodec = 'nvenc' | 'amf' | 'qsv' | 'mf' | 'x264'

export interface ClipSettings {
  enabled: boolean
  bufferSeconds: number
  resolution: ClipResolution
  fps: 30 | 60
  quality: ClipQuality
  encoder: ClipEncoder
  systemAudio: boolean
  microphone: boolean
  folder: string | null
  maxStorageGb: number
}

export interface Clip {
  instanceId: string
  instanceName: string
  fileName: string
  size: number
  createdAt: string | null
  durationMs: number | null
}

/** Aus der MP4-Datei gelesen (`clips::media::MediaInfo`). */
export interface ClipMediaInfo {
  durationMs: number
  width: number
  height: number
  hasVideo: boolean
  hasAudio: boolean
  /** Keyframe-Zeiten (ms, aufsteigend). */
  keyframesMs: number[]
}

/** Vorschau-Leiste: PNG-Raster aus kleinen Bildern (`clips::edit::ClipStrip`). */
export interface ClipStrip {
  frames: number
  cols: number
  rows: number
  frameWidth: number
  frameHeight: number
  /** Clip-Zeit zwischen zwei Bildern (ms). */
  intervalMs: number
  durationMs: number
}

/** `auto` = kopieren, wenn möglich; `fast` = immer kopieren (Start am Keyframe); `exact` = neu kodieren. */
export type TrimMode = 'auto' | 'fast' | 'exact'
export type TrimMethod = 'copy' | 'reencode'

/** Auftrag „Als neuen Clip speichern“. */
export interface TrimRequest {
  startMs: number
  endMs: number
  mode: TrimMode
  /** Name des neuen Clips (ohne `.mp4`). */
  name: string
}

export interface TrimPlan {
  startMs: number
  endMs: number
  method: TrimMethod
}

/** „Im Launcher öffnen“ aus dem Spiel (Ereignis `clip-open`). */
export interface ClipOpenRequest {
  instanceId: string
  fileName: string
}

export interface ClipUsage {
  usedBytes: number
  limitBytes: number
  count: number
  freeBytes: number | null
}

/** Warum gerade nicht aufgenommen wird. */
export type ClipReason = 'starting' | 'noWindow' | 'ffmpeg' | 'ffmpegFailed' | 'encoder' | 'error' | 'disabled'

export interface ClipState {
  instanceId: string
  buffer: boolean
  recording: boolean
  recordingMs: number
  reason: ClipReason | null
  encoder: ClipCodec | null
}

export type ClipFailure =
  | 'disabled'
  | 'starting'
  | 'noWindow'
  | 'ffmpeg'
  | 'ffmpegFailed'
  | 'encoder'
  | 'noFrames'
  | 'busy'
  | 'error'

export type ClipEvent =
  | ({ type: 'state' } & ClipState)
  | { type: 'saved'; instanceId: string; fileName: string; kind: 'clip' | 'recording'; seconds: number; removed: number }
  | { type: 'failed'; instanceId: string; code: ClipFailure }
  | { type: 'ended'; instanceId: string }
  | { type: 'ffmpeg'; state: 'downloading' | 'ready' | 'failed' }
  /** Clips wurden im Spiel eingeschaltet (Einstellungen neu laden). */
  | { type: 'enabled'; instanceId: string }

export interface FfmpegStatus {
  installed: boolean
  version: string
  downloadBytes: number
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

/** Was es auf diesem System gibt (aus `trs_core::platform::capabilities`). */
export interface PlatformCapabilities {
  platform: 'windows' | 'linux' | 'macos' | 'android' | 'ios'
  /** Windows-Firewall-Freigabe für die Java-Runtimes. */
  firewall: boolean
  trash: boolean
  /** Spiel-Clips aufnehmen (derzeit nur Windows). */
  clips: boolean
  /**
   * `auto` = eingebauter Updater, `package` = Paketverwaltung (.deb/.rpm/AUR), `flatpak`,
   * `portable` = ZIP-Version (neue Version von Hand von der Download-Seite),
   * `mobile` = eigener Kanal (Android: APK über `mobileUpdateInstall`, iOS: AltStore/SideStore).
   */
  updates: 'auto' | 'package' | 'flatpak' | 'portable' | 'mobile'
  /** Steam Deck, SteamOS oder gamescope (nur Linux): Big-Picture-Modus startet von selbst. */
  consoleSession: boolean
  /** Minecraft starten (Desktop über Java; mobil erst mit der Spiel-Engine). */
  gameLaunch: boolean
  /** Java suchen, installieren und wählen. */
  java: boolean
  /** Eigene Fensterknöpfe/Titelleiste (Minimieren, Maximieren, Schließen, Ziehen). */
  windowControls: boolean
  /** Push-Benachrichtigungen des Systems (mobil: Android über UnifiedPush oder Abholen, iOS über Abholen). */
  pushSupported: boolean
  /** Eingebaute Spiel-Engine als natives Plugin (mobil, folgt später). */
  gameEngine: boolean
}

/** Woher der Datenordner kommt. */
export type DataSource = 'default' | 'custom' | 'portable' | 'env'

/** Zusammenfassung vor einem Umzug (Datenordner oder Instanz). */
export interface MovePlan {
  /** Endgültiger Zielordner (bei einem nicht leeren Ordner ein Unterordner darin). */
  target: string
  bytes: number
  files: number
  /** Verknüpfungen, die nicht mitkopiert werden. */
  links: number
  free: number | null
  sameVolume: boolean
  enoughSpace: boolean
}

/** Wo eine Instanz liegt. */
export interface InstanceLocation {
  path: string
  custom: boolean
  defaultPath: string
}

/** Instanz an eigenem Ort, deren Ordner fehlt (Laufwerk getrennt). */
export interface UnavailableInstance {
  id: string
  path: string
}

/** Stand des mobilen Update-Kanals (`mobile_update_check`). */
export interface MobileUpdateStatus {
  current: string
  latest: string | null
  available: boolean
  notes: string
  pubDate: string | null
  /** Größe der APK in Bytes (Android). */
  size: number | null
  /** AltStore-/SideStore-Quelle (iOS). */
  altstoreSource: string | null
}

/** `started`: Installationsdialog des Systems offen; `permissionRequired`: erst „Unbekannte Apps installieren“ erlauben. */
export interface MobileInstallOutcome {
  status: 'started' | 'permissionRequired'
}

export interface AppInfo {
  version: string
  dataDir: string
  os: string
  capabilities: PlatformCapabilities
  /** Schutz der gespeicherten Anmeldedaten: `dpapi`, `keyring` (Schlüsselbund), `file` (nur Dateirechte) oder `none`. */
  tokenProtection: 'dpapi' | 'keyring' | 'file' | 'none'
  /** Woher der Datenordner kommt. */
  dataSource: DataSource
  /** Lässt sich der Datenordner verschieben (nicht portabel, nicht per Umgebungsvariable)? */
  dataMovable: boolean
  /** Gewählter eigener Datenordner, der beim Start fehlte (dann gilt der Standard). */
  missingDataDir: string | null
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

export type LaunchStage = 'mods' | 'version' | 'java' | 'loader' | 'libraries' | 'assets' | 'starting'

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
  /** Logger-Name aus dem log4j-XML (z. B. „TRS Client“), fehlt bei einfachen Textzeilen. */
  logger?: string
  message: string
}

/** Minecraft Bedrock (Microsoft Store), nur Windows */
export interface BedrockInfo {
  installed: boolean
}

export interface RunningGame {
  instanceId: string
  /** Prozess-Schlüssel: die Instanz-ID beim ersten Start, `<id>~2` usw. bei weiteren Starts derselben Instanz */
  key: string
  pid: number
  startedAt: string
}

export type GameEvent =
  | { type: 'started'; instanceId: string; key: string; pid: number }
  | { type: 'logs'; instanceId: string; key: string; lines: LogLine[] }
  | {
      type: 'exited'
      instanceId: string
      key: string
      exitCode: number | null
      crashed: boolean
      playSeconds: number
      diagnosis: Diagnosis | null
      /** Nach einem Absturz: ID der Analyse, die gleich als `crashAnalyzed` folgt. */
      crashId?: string
    }
  | { type: 'crashAnalyzed'; instanceId: string; crash: CrashAnalysis }
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
  kind:
    | 'corrupt_files'
    | 'out_of_memory'
    | 'wrong_java'
    | 'missing_dependency'
    | 'mod_conflict'
    | 'graphics_driver'
    | 'incompatible_mod'
  message: string
  /** Übersetzungs-Code der Meldung (`errors.<code>`), z. B. `process.crashOutOfMemory`. */
  code?: string
  /** Werte für die Übersetzung (`{name}` …). */
  params?: Record<string, string>
  canRepair: boolean
  /** Bei `incompatible_mod`: welche Mods sich laut Loader nicht vertragen. */
  conflict?: ModConflictInfo
  /** Bei `missing_dependency`: welche Mods fehlen – der Launcher kann sie installieren. */
  missing?: MissingModInfo
}

// --- Absturz-Helfer (Kern: `crates/core/src/crash`) ------------------------------

export type CrashKind =
  | 'known_issue'
  | 'duplicate_mod'
  | 'wrong_game_version'
  | 'wrong_loader_version'
  | 'incompatible_mod'
  | 'missing_dependency'
  | 'wrong_java'
  | 'out_of_memory'
  | 'corrupt_files'
  | 'graphics_driver'
  | 'mixin_conflict'
  | 'unknown'

/** Was der Launcher auf Knopfdruck tun kann (jede Änderung wird vorher bestätigt). */
export type CrashAction =
  | { type: 'disableMods'; files: string[] }
  | { type: 'installDependencies'; declarer: string | null; dependencies: string[] }
  | { type: 'removeDuplicates'; files: string[]; keep: string[] }
  | { type: 'setMemory'; fromMb: number; toMb: number }
  | { type: 'switchJava'; major: number | null }
  | { type: 'updateTrsClient' }
  | { type: 'fixConflict'; modId: string }
  | { type: 'repair' }

export interface CrashFinding {
  kind: CrashKind
  /** Genauere Art für den Text, z. B. `trsclient_essential`, `amd`, `reserve`. */
  variant?: string
  score: number
  params?: Record<string, string>
  /** Beteiligte Mods (IDs aus `CrashAnalysis.mods`). */
  mods: string[]
  /** Log-Zeilen, an denen es erkannt wurde (maskiert). */
  evidence: string[]
  actions: CrashAction[]
}

export interface CrashModRef {
  id: string
  name: string
  version?: string
  /** Datei im Mods-Ordner (ohne `.disabled`). */
  file?: string
  enabled: boolean
  iconUrl?: string
  /** Steckt in einer anderen Mod (Jar-in-Jar). */
  bundledIn?: string
}

export interface CrashAnalysis {
  id: string
  instanceId: string
  at: string
  exitCode: number | null
  playSeconds: number | null
  /** Log-Quellen (`crash-reports/…`, `live`, `logs/latest.log`). */
  sources: string[]
  /** Nach Wichtigkeit, der erste ist die Hauptursache; nie leer. */
  findings: CrashFinding[]
  mods: CrashModRef[]
  cause?: string
  firstFrame?: string
  excerpt: string[]
}

export interface CrashSummary {
  id: string
  at: string
  kind: CrashKind
  variant?: string
  mods: string[]
}

/** Aus der Loader-Meldung: `modId` braucht die Mods `dependencies` (Mod-IDs), die fehlen. */
export interface MissingModInfo {
  modId: string | null
  modName: string | null
  dependencies: string[]
}

/** Ergebnis von „fehlende Mods installieren“ (auch vor dem Start). */
export interface DependencyFix {
  added: string[]
  neededBy: string[]
  unresolved: string[]
}

/** Aus der Loader-Meldung: `modId` sollte getauscht werden, `otherId` ist der Grund. */
export interface ModConflictInfo {
  modId: string
  modName: string
  modVersion: string
  otherId: string
  otherName: string
  otherVersion: string | null
}

/** Ergebnis von „gegen passende Version tauschen“. */
export interface CompatReport {
  changes: { title: string; from: string | null; to: string; because: string }[]
  /** Konflikte ohne passende Version („Sodium 0.8.14 ↔ Iris 1.10.7“). */
  unresolved: string[]
}

export type ContentKind = 'mod' | 'resourcepack' | 'shaderpack' | 'datapack'

/** Woher Inhalte kommen. */
export type Platform = 'modrinth' | 'curseforge'

export interface ContentSource {
  /** Modrinth-Projekt-ID bzw. CurseForge-Projekt-ID (Zahl als Text). */
  projectId: string
  /** Modrinth-Versions-ID bzw. CurseForge-Datei-ID. */
  versionId: string
  versionNumber?: string
  /** Fehlt bei Modrinth. */
  platform?: Platform
}

/** Eine Datei in einer Gruppe gleicher Mod-IDs. */
export interface DuplicateFile {
  fileName: string
  version: string | null
}

/** Dieselbe Mod-ID in mehreren aktivierten Jars. `keep` bleibt, `disable` wird nur deaktiviert. */
export interface DuplicateModGroup {
  id: string
  name: string
  keep: DuplicateFile
  disable: DuplicateFile[]
}

export interface ContentItem {
  fileName: string
  kind: ContentKind
  enabled: boolean
  size: number
  title: string | null
  version: string | null
  description: string | null
  source: ContentSource | null
  author: string | null
  /** Modrinth-/CurseForge-CDN-URL oder Data-URL aus der Datei */
  iconUrl: string | null
  slug: string | null
}

/** Datei, deren Autor Downloads über andere Apps nicht erlaubt – der Nutzer lädt sie selbst. */
export interface BlockedFile {
  projectId: string
  fileId: string
  title: string
  fileName: string
  kind: ContentKind
  sha1: string | null
  size: number
  /** Dateiseite auf curseforge.com */
  url: string
  iconUrl: string | null
  replace?: string
  versionNumber?: string
}

export interface CurseForgeInstallOutcome {
  files: string[]
  blocked: BlockedFile[]
}

export interface CurseForgePackResult {
  instance: Instance
  blocked: BlockedFile[]
}

export interface AdoptResult {
  adopted: string[]
  pending: BlockedFile[]
  watchFolder: string | null
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

/**
 * Kategorie für die Filterleiste. Modrinth: `icon` ist SVG-Markup – nur als
 * <img>-Data-URL anzeigen. CurseForge: `name` ist die Kategorie-ID, `label`
 * der Anzeigename, `iconUrl` ein Bild von media.forgecdn.net.
 */
export interface CategoryTag {
  name: string
  projectType: string
  header: string
  icon: string | null
  label?: string
  iconUrl?: string
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
  /** Fehlt = Modrinth. */
  platform?: Platform
  kind: ContentKind
  fileName: string
  projectId: string
  versionId: string
  versionNumber: string
  /** Version bewusst so gewählt (evtl. älter), damit es mit dieser Mod läuft. */
  compatWith?: string
}

export interface PackProgress {
  /** Einmal, sobald die Instanz angelegt ist (die Installation läuft dann noch). */
  instanceId?: string
  /** Beim Laden der Dateien: wie viele schon da sind. */
  doneFiles?: number
  totalFiles?: number
  phase: 'pack' | 'files' | 'overrides'
  percent: number
}

/** Einstellung „Bei Modpacks: TRS Client“. */
export type ModpackTrsPolicy = 'ask' | 'always' | 'never'

/** Wie stark sich eine Mod mit dem TRS Client überschneidet (`zoom` = nur Hinweis). */
export type TrsConflictKind = 'clientMod' | 'minimap' | 'hud' | 'zoom'

export interface TrsConflict {
  name: string
  kind: TrsConflictKind
}

/** „Mit oder ohne TRS Client?“ – vom Kern für ein Pack bzw. eine Instanz berechnet. */
export interface TrsOffer {
  /** `false` = keine Frage (Vanilla bleibt wie bisher). */
  applies: boolean
  /** Es gibt einen TRS-Client-Build für Loader + Version. */
  supported: boolean
  unsupported: { kind: 'noBuild'; loader: LoaderKind; gameVersion: string } | null
  /** Vorauswahl: `true` = „Mit TRS Client“. */
  recommended: boolean
  conflicts: TrsConflict[]
  policy: ModpackTrsPolicy
}

/** Vorschau eines Modpacks vor der Installation. */
export interface PackPreview {
  name: string
  gameVersion: string
  loader: Loader
  modCount: number
  /** Genau diese Version wird installiert. */
  versionId: string | null
  trsClient: TrsOffer
}

/** Gewählte Modpack-Datei (Pfad bleibt im Kern). */
export interface PickedPack {
  token: number
  fileName: string
  preview: PackPreview
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
  /** „Schnell verbinden“: vorab aufgelöst und gemessen (nur Server-Seite). */
  fastConnect?: FastConnect | undefined
}

export interface FastConnect {
  family: 'ipv4' | 'ipv6'
  /** TCP-Verbindungszeit der schnellsten Adresse. */
  connectMs: number
  /** Bekannte Adressen des Ziels. */
  addresses: number
}

/** Screenshot; `path` ist fürs Webview freigegeben (convertFileSrc). */
export interface ImageEntry {
  name: string
  path: string | null
  size: number
  date: string | null
}

/** Server aus der `servers.dat` einer Instanz (Tab „Welten“). */
export interface InstanceServer {
  /** Position in der servers.dat; `null` = Launcher-Server, der beim nächsten Start dazukommt. */
  index: number | null
  name: string
  address: string
  /** Geprüfte `data:image/png;base64,…`-URL. */
  icon: string | null
  acceptTextures: boolean | null
  /** Gehört zur Launcher-Serverliste (alle Instanzen). */
  launcherId: string | null
  joinable: boolean
}

/** Ergebnis des Ping-Tests für einen Eintrag von `instanceServers`. */
export interface InstancePing {
  index: number | null
  address: string
  status: ServerStatus
}

export type GameMode = 'survival' | 'creative' | 'adventure' | 'spectator'

export interface WorldInfo {
  folder: string
  name: string
  /** Fürs Webview freigegeben (convertFileSrc). */
  iconPath: string | null
  lastPlayed: string | null
  gameMode: GameMode | null
  hardcore: boolean
  cheats: boolean
  version: string | null
  size: number | null
}

/** Eintrag im Dateibrowser (Tab „Dateien“). */
export interface FileEntry {
  name: string
  dir: boolean
  size: number
  created: string | null
  modified: string | null
}

export interface DirListing {
  /** Relativer Pfad mit `/`, Spielordner = "". */
  path: string
  entries: FileEntry[]
  truncated: boolean
}

export interface ImportReport {
  files: number
  bytes: number
  skipped: { name: string; reason: 'symlink' | 'invalidName' | 'unsupported' | 'inside' }[]
}

export type LogSourceKind = 'game' | 'crash' | 'launcher'

/** Ältere Log-Datei bzw. Absturzbericht einer Instanz. */
export interface LogSource {
  id: string
  kind: LogSourceKind
  name: string
  size: number
  modified: string | null
}

export interface LogText {
  text: string
  truncated: boolean
}

export interface QrMatrix {
  size: number
  modules: number[]
}

export type ImportSource =
  | 'vanilla'
  | 'prism'
  | 'multimc'
  | 'curseforge'
  | 'modrinth'
  | 'folder'
  | 'lunar'
  | 'badlion'
  | 'feather'
  | 'oneclient'
  | 'atlauncher'
  | 'gdlauncher'
  | 'gdlaunchercarbon'
  | 'tlauncher'

/** Hinweise zur Vorschau eines Imports (erklärt im Dialog). */
export type ImportNote = 'clientModsSkipped' | 'sharedGameDir' | 'loaderMapped' | 'curseForgeDownloads'

export interface ImportCandidate {
  id: string
  source: ImportSource
  name: string
  gameVersion: string
  loader: Loader
  modCount: number
  worldCount: number
  resourcePackCount: number
  shaderPackCount: number
  /** options.txt (Einstellungen, Tastenbelegung) kommt mit. */
  hasOptions: boolean
  /** servers.dat (Serverliste) kommt mit. */
  hasServers: boolean
  /** Inhalte mit bekannter Herkunft (bleiben aktualisierbar). */
  trackedCount: number
  /** Fehlende Dateien, die von CurseForge geladen werden. */
  missingCount: number
  notes: ImportNote[]
  versionGuessed: boolean
  /** „Mit oder ohne TRS Client“ für diese Instanz. */
  trsClient: TrsOffer | null
}

/** Ein Launcher, dessen Daten auf diesem PC liegen. */
export interface DetectedLauncher {
  source: ImportSource
  instances: number
  /** false = erkannt, speichert aber keine lesbare Liste. */
  supported: boolean
}

export interface ImportOverview {
  candidates: ImportCandidate[]
  launchers: DetectedLauncher[]
}

export interface ImportResult {
  instance: Instance
  tracked: number
  downloaded: number
  blocked: number
  failed: number
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
  | 'settings_changed'
  | 'pack_updated'

export interface HistoryEntry {
  at: string
  kind: HistoryKind
  subject?: string
  from?: string
  to?: string
  detail?: string
  seconds?: number
  /** Absturz-Analyse zu diesem Eintrag (Absturz-Helfer). */
  crash?: string
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
  kind: 'source' | 'issues' | 'wiki' | 'discord' | 'modrinth' | 'curseforge'
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
  platform: Platform
  kind: ContentKind
  fileName: string
  title: string
  iconUrl: string | null
  projectId: string
  currentVersion: string | null
  status: 'compatible' | 'update' | 'missing'
  targetVersionId: string | null
  targetVersionNumber: string | null
  /** Version so gewählt, damit sie mit dieser Mod läuft („Iris 1.11.4+mc26.1.2“). */
  compatWith?: string | null
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
  /** Standard-Textur dieses Kontos (aus einem installierten Client), sonst `null`. */
  defaultSkin: string | null
  /** Armbreite dieser Standard-Textur. */
  defaultVariant: SkinVariant
  /** Name des Standard-Skins (`steve`, `alex`, `ari`, …). */
  defaultSkinName: string
  /** Minecraft-Version, aus der die Standard-Textur stammt, sonst `null`. */
  defaultSkinVersion: string | null
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

/** Woher ein Skin beim Hinzufügen kommt (`minecraft` = offizieller Launcher). */
export type SkinImportSource = 'file' | 'url' | 'player' | 'minecraft' | 'prism' | 'modrinth' | 'atlauncher'

/** Vorgemerkter Skin (geprüft im Kern) – übernommen wird er erst per Marke. */
export interface SkinImportCandidate {
  token: string
  /** Namensvorschlag (Datei-, Spieler- oder Launcher-Name). */
  name: string
  /** Erkanntes bzw. mitgeliefertes Modell. */
  variant: SkinVariant
  /** Textur als Data-URL. */
  texture: string
  source: SkinImportSource
  /** Genau dieses Bild liegt schon in der Sammlung. */
  duplicate: boolean
}

export interface SkinImportFailure {
  name: string
  error: string
  errorInfo: CommandError
}

export interface SkinImportBatch {
  candidates: SkinImportCandidate[]
  failed: SkinImportFailure[]
}

export interface LauncherSkinScan {
  candidates: SkinImportCandidate[]
  /** Launcher, deren Daten auf diesem PC liegen (auch ohne Skins). */
  found: SkinImportSource[]
  /** Launcher, die hier liegen, aber keine lesbare Skin-Liste speichern. */
  withoutSkins: ImportSource[]
}

export interface SkinImportRequest {
  token: string
  name?: string | null
  variant?: SkinVariant | null
}

export interface SkinImportReport {
  added: LibrarySkin[]
  failed: SkinImportFailure[]
}

/** Auswählbarer Skin: Standard-Skin (`wide/steve`) oder aus einem offiziellen Paket (`pack/<paket>/<skin>`). */
export interface SelectableSkin {
  id: string
  /** Standard-Skins: `steve`, `alex`, … (klein); Paket-Skins: offizieller Name. */
  name: string
  variant: SkinVariant
  /** Textur als Data-URL. */
  texture: string
}

/** Alle Standard-Skins aus einem installierten Client (leer ohne installierte Version). */
export interface BuiltinSkins {
  version: string | null
  skins: SelectableSkin[]
}

/** Offizielles Skin-Paket von minecraft.net. */
export interface SkinPack {
  id: string
  name: string
  /** Erscheinungstag `YYYY-MM-DD`. */
  released: string
  skins: SelectableSkin[]
}

/** Gewünschter Skin: aus der Sammlung, getragener mit anderem Modell, Standard oder ein auswählbarer Skin. */
export type SkinChange =
  | { kind: 'library'; id: string; variant: SkinVariant }
  | { kind: 'current'; variant: SkinVariant }
  | { kind: 'default' }
  | { kind: 'builtin'; id: string; variant: SkinVariant }

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

export type NewsSource = 'patchNotes' | 'mojang' | 'modrinth' | 'launcher' | 'trs'

/** TRS-News: Texte einer Sprache (Markdown im Kern gesäubert, Bilder nur von der eigenen Website). */
export interface NewsTexts {
  title: string
  summary: string
  markdown: string
}

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
  /** TRS-News: Texte je Sprache (`en` immer). */
  texts?: Record<string, NewsTexts>
  /** TRS-News: Autor, sonst TRS-Team. */
  author?: string
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
  phase: 'hashing' | 'lookup' | 'writing' | 'uploading'
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
  /** Liegt im gemeinsamen Screenshot-Ordner (wird nur einmal gezeigt). */
  shared: boolean
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
  | 'server-export'
  | 'ffmpeg'
  | 'shared-folders'
  | 'move'

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

/** Woher ein Preset-Eintrag stammt. */
export type PresetSource = 'modrinth' | 'curseforge'

export interface PresetItem {
  source: PresetSource
  projectId: string
  title: string
  iconUrl: string | null
  kind: ContentKind
}

/**
 * Fertige TRS-Presets (Name/Beschreibung: `presets.builtin.<key>`). `fpsBoost`,
 * `fpsShaderLite` und `fpsShader` sind die drei Stufen des FPS-Boosts
 * (Max FPS / Shader leicht / Shader schön) – höchstens eine davon ist gewählt.
 */
export type BuiltinPreset = 'fpsBoost' | 'fpsShaderLite' | 'fpsShader' | 'nvidium' | 'voiceChat' | 'replay'

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
  /** Symbol (`PRESET_ICONS`) – fertige Presets haben ein festes. */
  icon: string
  /** Akzentfarbe (`PRESET_COLORS`). */
  color: string
}

export interface PresetInput {
  name: string
  auto: boolean
  items: PresetItem[]
  icon?: string | null
  color?: string | null
}

/** Ein Inhalt für „Aus Modpack übernehmen“ (ohne Projekt = nicht übernehmbar). */
export interface PresetPickItem {
  source: PresetSource | null
  projectId: string | null
  title: string
  iconUrl: string | null
  kind: ContentKind
  /** Modrinth-Kategorien (Slugs) bzw. CurseForge-Namen. */
  categories: string[]
  performance: boolean
  /** Nur im Client nötig; `null` = unbekannt. */
  clientOnly: boolean | null
  fileName: string | null
}

export interface PresetPickList {
  name: string
  gameVersion: string | null
  loader: LoaderKind | null
  items: PresetPickItem[]
}

/** Pflicht-Abhängigkeit, die beim Installieren automatisch mitkommt. */
export interface PresetDepRef {
  source: PresetSource
  projectId: string
  title: string
  iconUrl: string | null
  /** Nur bei diesen Loadern nötig; leer = immer. */
  loaders: string[]
}

export interface PresetItemDeps {
  source: PresetSource
  projectId: string
  deps: PresetDepRef[]
}

export type PresetConflictReason = 'renderer' | 'shaders' | 'minimap' | 'zoom' | 'nvidium' | 'declared' | 'trsClient'

export interface PresetItemRef {
  source: PresetSource
  projectId: string
  title: string
}

export interface PresetConflict {
  a: PresetItemRef
  /** `null` bei `trsClient`. */
  b: PresetItemRef | null
  reason: PresetConflictReason
}

export interface PresetCheck {
  deps: PresetItemDeps[]
  conflicts: PresetConflict[]
}

/** Grafik-Modus des TRS Clients: nur Leistungs-Schalter ohne Optik-Verlust oder „Max FPS“. */
export type FpsMode = 'pretty' | 'max'

export type PresetItemStatus =
  | 'installed'
  | 'alreadyInstalled'
  | 'duplicate'
  | 'notAvailable'
  | 'missingDependency'
  | 'incompatible'
  | 'needsLoader'
  | 'failed'
  | 'swapped'
  | 'bundled'

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
  /** Version bewusst so gewählt, damit es mit dieser Mod läuft („Iris 1.10.7“). */
  compatWith: string | null
}

export interface PresetApplyReport {
  gameVersion: string
  loader: LoaderKind
  items: PresetItemOutcome[]
  files: string[]
  dependencies: number
  /** Shaderpaket, das jetzt in Iris eingeschaltet ist (Dateiname). */
  shaderPack: string | null
}

export interface PresetProgress {
  phase: 'resolve' | 'install'
  done: number
  total: number
  title: string | null
}
