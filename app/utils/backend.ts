import { Channel, invoke, isTauri } from '@tauri-apps/api/core'
import type {
  Account,
  AppInfo,
  CommandError,
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
  PackProgress,
  ProjectCard,
  ProjectDetails,
  RunningGame,
  Server,
  ServerInput,
  ServerStatus,
  Settings,
  StageProgress,
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
  openDataDir: () => call<void>('open_data_dir'),

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
  changeInstanceVersion: (id: string, gameVersion: string, loader: Loader) =>
    call<Instance>('change_instance_version', { id, gameVersion, loader }),
  instanceHistory: (id: string) => call<HistoryEntry[]>('instance_history', { id }),

  getVersionManifest: (forceRefresh = false) =>
    call<VersionManifest>('get_version_manifest', { forceRefresh }),

  /**
   * Löst erst auf, wenn das Spiel gestartet ist; Fortschritt kommt über `onProgress`.
   * `joinServer`: ID aus der Server-Liste – das Spiel verbindet sich dann direkt.
   */
  launchInstance: (id: string, joinServer: string | null, onProgress: (p: StageProgress) => void) =>
    call<number>('launch_instance', { id, joinServer, onProgress: channel(onProgress) }),
  stopInstance: (id: string) => call<boolean>('stop_instance', { id }),
  runningGames: () => call<RunningGame[]>('running_games'),
  getGameLogs: (id: string) => call<LogLine[]>('get_game_logs', { id }),
  repairInstance: (id: string, onProgress: (p: StageProgress) => void) =>
    call<void>('repair_instance', { id, onProgress: channel(onProgress) }),
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
  installedProjects: (id: string) => call<string[]>('installed_projects', { id }),
  checkContentUpdates: (id: string) => call<ContentUpdate[]>('check_content_updates', { id }),
  applyContentUpdate: (id: string, update: ContentUpdate) =>
    call<string>('apply_content_update', {
      id,
      kind: update.kind,
      fileName: update.fileName,
      versionId: update.versionId,
    }),
  installPerformancePack: (id: string) => call<string[]>('install_performance_pack', { id }),
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
  modrinthVersions: (id: string, projectId: string, kind: ContentKind) =>
    call<ModrinthVersion[]>('modrinth_versions', { id, projectId, kind }),
  /** Ohne `versionId` die neueste passende Version; Pflicht-Abhängigkeiten kommen immer mit. */
  modrinthInstall: (id: string, projectId: string, kind: ContentKind, versionId: string | null = null) =>
    call<string[]>('modrinth_install', { id, projectId, kind, versionId }),
  installModpack: (projectId: string, onProgress: (p: PackProgress) => void) =>
    call<Instance>('install_modpack', { projectId, onProgress: channel(onProgress) }),

  listServers: () => call<Server[]>('list_servers'),
  addServer: (server: ServerInput) => call<Server>('add_server', { server }),
  updateServer: (id: string, server: ServerInput) => call<Server>('update_server', { id, server }),
  removeServer: (id: string) => call<void>('remove_server', { id }),
  pingServer: (id: string) => call<ServerStatus>('ping_server', { id }),

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
