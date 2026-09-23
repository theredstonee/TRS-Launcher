import { Channel, invoke, isTauri } from '@tauri-apps/api/core'
import type {
  Account,
  BulkAction,
  BulkResult,
  JavaCheck,
  JavaInstall,
  LoaderKind,
  LoaderVersionInfo,
  StorageStats,
  UploadResult,
  VerifyReport,
  AppInfo,
  ClientModStatus,
  CategoryTag,
  CommandError,
  ExportEntry,
  ExportOptions,
  ExportProgress,
  ExportSummary,
  GalleryShot,
  LibrarySkin,
  NewsFeed,
  SkinChanges,
  SkinProfile,
  SkinSyncStatus,
  SkinVariant,
  ContentItem,
  ContentKind,
  ContentUpdate,
  DeviceCode,
  HistoryEntry,
  ImportCandidate,
  ImportProgress,
  ImageEntry,
  Instance,
  InstanceOverrides,
  Loader,
  LogLine,
  MigrationItem,
  ModrinthSearchParams,
  ModrinthSearchResult,
  ModrinthVersion,
  NewInstance,
  NewTaskRecord,
  PackProgress,
  ProjectCard,
  ProjectDetails,
  RunningGame,
  Server,
  ServerInput,
  ServerStatus,
  Settings,
  StageProgress,
  TaskRecord,
  VersionManifest,
} from '~/types'

export class BackendError extends Error {
  constructor(
    public readonly kind: string,
    message: string,
  ) {
    super(message)
    this.name = 'BackendError'
  }
}

function isCommandError(e: unknown): e is CommandError {
  return typeof e === 'object' && e !== null && 'kind' in e && 'message' in e
}

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!isTauri()) {
    throw new BackendError('no_backend', 'Das Backend ist nur in der Desktop-App verfügbar.')
  }
  try {
    return await invoke<T>(command, args)
  } catch (e) {
    if (isCommandError(e)) throw new BackendError(e.kind, e.message)
    throw new BackendError('unknown', 'Ein unerwarteter Fehler ist aufgetreten.')
  }
}

function channel<T>(onMessage: (message: T) => void): Channel<T> {
  const ch = new Channel<T>()
  ch.onmessage = onMessage
  return ch
}

/** Typisierte Wrapper um die Rust-Commands aus `src-tauri/src/commands`. */
export const backend = {
  appInfo: () => call<AppInfo>('app_info'),
  /** TRS Client: mitgelieferte Version und ein schon geladenes Update aus dem Kanal. */
  clientModStatus: () => call<ClientModStatus>('client_mod_status'),
  openDataDir: () => call<void>('open_data_dir'),
  firewallStatus: () => call<{ total: number; missing: number }>('firewall_status'),
  /** Eine Windows-Admin-Abfrage; danach fragt Windows bei keiner Instanz mehr nach dem Netzwerk. */
  firewallAllowAll: () => call<number>('firewall_allow_all'),

  getSettings: () => call<Settings>('get_settings'),
  updateSettings: (settings: Settings) => call<Settings>('update_settings', { settings }),

  listInstances: () => call<Instance[]>('list_instances'),
  getInstance: (id: string) => call<Instance>('get_instance', { id }),
  createInstance: (instance: NewInstance) => call<Instance>('create_instance', { instance }),
  updateInstance: (id: string, update: { name: string; overrides: InstanceOverrides }) =>
    call<Instance>('update_instance', { id, update }),
  deleteInstance: (id: string) => call<void>('delete_instance', { id }),
  duplicateInstance: (id: string, name: string) => call<Instance>('duplicate_instance', { id, name }),
  openInstanceDir: (id: string) => call<void>('open_instance_dir', { id }),
  /** Öffnet den Bilddialog; `null` = abgebrochen. */
  pickInstanceIcon: (id: string) => call<Instance | null>('pick_instance_icon', { id }),
  removeInstanceIcon: (id: string) => call<Instance>('remove_instance_icon', { id }),
  /** Öffnet den Bilddialog für das Banner; `null` = abgebrochen. */
  pickInstanceBanner: (id: string) => call<Instance | null>('pick_instance_banner', { id }),
  /** Nimmt einen Screenshot der Instanz als Banner (Rust prüft den Dateinamen). */
  setInstanceBannerFromScreenshot: (id: string, fileName: string) =>
    call<Instance>('set_instance_banner_screenshot', { id, fileName }),
  removeInstanceBanner: (id: string) => call<Instance>('remove_instance_banner', { id }),
  changeInstanceVersion: (id: string, gameVersion: string, loader: Loader) =>
    call<Instance>('change_instance_version', { id, gameVersion, loader }),
  instanceHistory: (id: string) => call<HistoryEntry[]>('instance_history', { id }),
  setInstanceGroup: (id: string, group: string | null) => call<Instance>('set_instance_group', { id, group }),
  loaderVersions: (kind: LoaderKind, gameVersion: string) =>
    call<LoaderVersionInfo[]>('loader_versions', { kind, gameVersion }),
  latestLoaderVersion: (kind: LoaderKind, gameVersion: string) =>
    call<string | null>('latest_loader_version', { kind, gameVersion }),
  reinstallInstance: (id: string, onProgress: (p: StageProgress) => void, taskId: string | null = null) =>
    call<void>('reinstall_instance', { id, onProgress: channel(onProgress), taskId }),

  /** Öffnet den Dateidialog für java.exe/javaw.exe; `null` = abgebrochen. */
  pickJavaPath: () => call<string | null>('pick_java_path'),
  checkJava: (path: string) => call<JavaCheck>('check_java', { path }),
  detectJava: () => call<JavaInstall[]>('detect_java'),
  installJava: (major: number, onProgress: (percent: number) => void, taskId: string | null = null) =>
    call<string>('install_java', { major, onProgress: channel(onProgress), taskId }),
  storageStats: () => call<StorageStats>('storage_stats'),
  cleanUnusedStorage: () => call<number>('clean_unused_storage'),
  verifyStorage: () => call<VerifyReport>('verify_storage'),

  getVersionManifest: (forceRefresh = false) =>
    call<VersionManifest>('get_version_manifest', { forceRefresh }),

  /**
   * Löst erst auf, wenn das Spiel gestartet ist; Fortschritt kommt über `onProgress`.
   * `joinServer`: ID aus der Server-Liste – das Spiel verbindet sich dann direkt.
   */
  launchInstance: (
    id: string,
    joinServer: string | null,
    onProgress: (p: StageProgress) => void,
    taskId: string | null = null,
  ) => call<number>('launch_instance', { id, joinServer, onProgress: channel(onProgress), taskId }),
  stopInstance: (id: string) => call<boolean>('stop_instance', { id }),
  runningGames: () => call<RunningGame[]>('running_games'),
  getGameLogs: (id: string) => call<LogLine[]>('get_game_logs', { id }),
  repairInstance: (id: string, onProgress: (p: StageProgress) => void, taskId: string | null = null) =>
    call<void>('repair_instance', { id, onProgress: channel(onProgress), taskId }),
  /** Lädt den Log geschwärzt auf mclo.gs hoch; liefert den Link. */
  shareLog: (id: string) => call<string>('share_log', { id }),

  listAccounts: () => call<Account[]>('list_accounts'),
  loginBrowser: () => call<Account>('login_browser'),
  loginDeviceCode: (onCode: (code: DeviceCode) => void) =>
    call<Account>('login_device_code', { onCode: channel(onCode) }),
  cancelLogin: () => call<void>('cancel_login'),
  setActiveAccount: (id: string) => call<void>('set_active_account', { id }),
  removeAccount: (id: string) => call<void>('remove_account', { id }),

  listContent: (id: string, kind: ContentKind) => call<ContentItem[]>('list_content', { id, kind }),
  setContentEnabled: (id: string, kind: ContentKind, fileName: string, enabled: boolean) =>
    call<void>('set_content_enabled', { id, kind, fileName, enabled }),
  deleteContent: (id: string, kind: ContentKind, fileName: string) =>
    call<void>('delete_content', { id, kind, fileName }),
  bulkContent: (id: string, action: BulkAction, targets: { kind: ContentKind; fileName: string }[]) =>
    call<BulkResult>('bulk_content', { id, action, targets }),
  /** Öffnet den Dateidialog (Mehrfachauswahl); `null` = abgebrochen. */
  pickContentFiles: (id: string) => call<UploadResult[] | null>('pick_content_files', { id }),
  /** Übernimmt die zuletzt ins Fenster gezogenen Dateien (Marke aus dem `file-drop`-Event). */
  addDroppedFiles: (id: string, token: number) => call<UploadResult[]>('add_dropped_files', { id, token }),
  installedProjects: (id: string) => call<string[]>('installed_projects', { id }),
  checkContentUpdates: (id: string) => call<ContentUpdate[]>('check_content_updates', { id }),
  applyContentUpdate: (id: string, update: ContentUpdate, taskId: string | null = null) =>
    call<string>('apply_content_update', {
      id,
      kind: update.kind,
      fileName: update.fileName,
      versionId: update.versionId,
      taskId,
    }),
  installPerformancePack: (id: string, taskId: string | null = null) =>
    call<string[]>('install_performance_pack', { id, taskId }),
  /** Icons, Titel, Autoren von Modrinth nachladen; `true` = Liste neu laden. */
  refreshContentMeta: (id: string) => call<boolean>('refresh_content_meta', { id }),
  /** Neuere passende Versionen seit der installierten, mit Changelog. */
  contentChangelog: (id: string, projectId: string, kind: ContentKind, installedVersionId: string) =>
    call<ModrinthVersion[]>('content_changelog', { id, projectId, kind, installedVersionId }),
  planContentMigration: (id: string) => call<MigrationItem[]>('plan_content_migration', { id }),

  modrinthProject: (projectId: string) => call<ProjectDetails>('modrinth_project', { projectId }),
  modrinthProjectVersions: (projectId: string) => call<ModrinthVersion[]>('modrinth_project_versions', { projectId }),
  modrinthProjects: (ids: string[]) => call<ProjectCard[]>('modrinth_projects', { ids }),
  /** Öffnet einen HTTPS-Link im Standardbrowser (Rust prüft die URL erneut). */
  openExternalUrl: (url: string) => call<void>('open_external_url', { url }),

  modrinthSearch: (params: ModrinthSearchParams) => call<ModrinthSearchResult>('modrinth_search', { params }),
  /** Alle Modrinth-Kategorien (der Kern cacht sie einen Tag). */
  modrinthCategories: () => call<CategoryTag[]>('modrinth_categories'),
  modrinthVersions: (id: string, projectId: string, kind: ContentKind) =>
    call<ModrinthVersion[]>('modrinth_versions', { id, projectId, kind }),
  /** Ohne `versionId` die neueste passende Version; Pflicht-Abhängigkeiten kommen immer mit. */
  modrinthInstall: (
    id: string,
    projectId: string,
    kind: ContentKind,
    versionId: string | null = null,
    taskId: string | null = null,
  ) => call<string[]>('modrinth_install', { id, projectId, kind, versionId, taskId }),
  /** `taskId`: Aufgabe im Kern (Abbrechen, Pause, Byte-Stand über `task-progress`). */
  installModpack: (projectId: string, onProgress: (p: PackProgress) => void, taskId: string | null = null) =>
    call<Instance>('install_modpack', { projectId, onProgress: channel(onProgress), taskId }),

  listServers: () => call<Server[]>('list_servers'),
  addServer: (server: ServerInput) => call<Server>('add_server', { server }),
  updateServer: (id: string, server: ServerInput) => call<Server>('update_server', { id, server }),
  removeServer: (id: string) => call<void>('remove_server', { id }),
  pingServer: (id: string) => call<ServerStatus>('ping_server', { id }),

  /** Profil des aktiven Accounts (Skin, Modell, Umhänge) – Texturen als Data-URL. */
  skinProfile: () => call<SkinProfile>('skin_profile'),
  skinLibrary: () => call<LibrarySkin[]>('skin_library'),
  /** Öffnet den Dateidialog für ein 64×64-PNG; `null` = abgebrochen. */
  addSkinFile: (name: string, variant: SkinVariant) => call<LibrarySkin | null>('add_skin_file', { name, variant }),
  saveActiveSkin: (name: string) => call<LibrarySkin>('save_active_skin', { name }),
  deleteSkin: (id: string) => call<void>('delete_skin', { id }),
  /**
   * Schickt den fertigen Entwurf (nur den Unterschied) an die Warteschlange im
   * Kern und kehrt sofort zurück. `account` = UUID des gezeigten Profils.
   */
  applySkinChanges: (account: string, changes: SkinChanges) =>
    call<SkinSyncStatus>('apply_skin_changes', { account, changes }),
  skinSyncStatus: () => call<SkinSyncStatus>('skin_sync_status'),
  /** Noch nicht gesendete Änderungen verwerfen. */
  cancelSkinSync: () => call<SkinSyncStatus>('cancel_skin_sync'),

  /** Neuigkeiten für die Startseite (Kern cacht sie; `force` lädt neu). */
  getNews: (force = false) => call<NewsFeed>('get_news', { force }),
  /** Lädt ein Feed-Bild in den Cache und gibt den freigegebenen Pfad zurück. */
  newsImage: (url: string) => call<string | null>('news_image', { url }),
  /** Voller Patchnotes-Text (HTML) – nur über MarkdownView anzeigen. */
  patchNotesBody: (contentPath: string) => call<string>('patch_notes_body', { contentPath }),

  /** Ordner und Dateien der Instanz, die exportiert werden können. */
  exportCandidates: (id: string) => call<ExportEntry[]>('export_candidates', { id }),
  /** Fragt nach dem Speicherort und schreibt das .mrpack; `null` = abgebrochen. */
  exportModpack: (id: string, options: ExportOptions, onProgress: (p: ExportProgress) => void) =>
    call<ExportSummary | null>('export_modpack', { id, options, onProgress: channel(onProgress) }),
  /** Öffnet eine .mrpack-Datei und legt daraus eine Instanz an; `null` = abgebrochen. */
  importModpackFile: (onProgress: (p: PackProgress) => void, taskId: string | null = null) =>
    call<string | null>('import_modpack_file', { onProgress: channel(onProgress), taskId }),

  /** Laufende Aufgabe abbrechen; `false` = läuft nicht (mehr). */
  cancelTask: (taskId: string) => call<boolean>('cancel_task', { taskId }),
  /** Downloads der Aufgabe anhalten bzw. fortsetzen. */
  pauseTask: (taskId: string, paused: boolean) => call<boolean>('pause_task', { taskId, paused }),
  /** Verlauf fertiger Aufgaben, neueste zuerst (höchstens 50). */
  taskHistory: () => call<TaskRecord[]>('task_history'),
  recordTask: (record: NewTaskRecord) => call<TaskRecord>('record_task', { record }),
  removeTaskRecord: (id: string) => call<void>('remove_task_record', { id }),
  clearTaskHistory: () => call<void>('clear_task_history'),

  /** Screenshots aller Instanzen, neueste zuerst. */
  allScreenshots: () => call<GalleryShot[]>('all_screenshots'),
  /** Vorschaubild (wird im Kern erzeugt und zwischengespeichert). */
  screenshotThumbnail: (id: string, fileName: string) =>
    call<string | null>('screenshot_thumbnail', { id, fileName }),
  screenshotImage: (id: string, fileName: string) => call<string | null>('screenshot_image', { id, fileName }),
  copyScreenshot: (id: string, fileName: string) => call<void>('copy_screenshot', { id, fileName }),
  revealScreenshot: (id: string, fileName: string) => call<void>('reveal_screenshot', { id, fileName }),
  /** Löschen – wenn möglich in den Papierkorb. */
  trashScreenshot: (id: string, fileName: string) => call<void>('trash_screenshot', { id, fileName }),

  listScreenshots: (id: string) => call<ImageEntry[]>('list_screenshots', { id }),
  openScreenshot: (id: string, fileName: string) => call<void>('open_screenshot', { id, fileName }),
  deleteScreenshot: (id: string, fileName: string) => call<void>('delete_screenshot', { id, fileName }),
  listWorlds: (id: string) => call<ImageEntry[]>('list_worlds', { id }),

  scanImports: () => call<ImportCandidate[]>('scan_imports'),
  /** Öffnet den Ordnerdialog; `null` = abgebrochen. */
  pickImportFolder: () => call<ImportCandidate[] | null>('pick_import_folder'),
  importInstance: (
    id: string,
    gameVersion: string | null,
    loader: Loader | null,
    onProgress: (p: ImportProgress) => void,
  ) => call<Instance>('import_instance', { id, gameVersion, loader, onProgress: channel(onProgress) }),
}

export function isCancelled(e: unknown): boolean {
  return e instanceof BackendError && e.kind === 'cancelled'
}

export function errorMessage(e: unknown): string {
  return e instanceof BackendError ? e.message : 'Ein unerwarteter Fehler ist aufgetreten.'
}
