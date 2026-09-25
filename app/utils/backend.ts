import { Channel, invoke, isTauri } from '@tauri-apps/api/core'
import { hasKey, t, tKey } from './i18n'
import { z } from 'zod'
import {
  trsAdminCapeSchema,
  trsAdminStatsSchema,
  trsAdminUserSchema,
  trsBlockedSchema,
  trsCapeSchema,
  trsCodeSchema,
  trsFriendRequestResultSchema,
  trsFriendSchema,
  trsFriendsSchema,
  trsMeSchema,
  trsParse,
  trsPlayerCapeSchema,
  trsRedeemSchema,
  trsStatusSchema,
  trsSyncStatusSchema,
  trsUserRefSchema,
  type TrsPrivacy,
  type TrsReportReason,
  type TrsReviewList,
} from './trs'
import type {
  Account,
  AdoptResult,
  BlockedFile,
  CurseForgeInstallOutcome,
  CurseForgePackResult,
  Platform,
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
  Preset,
  PresetApplyReport,
  PresetInput,
  PresetProgress,
  ExportEntry,
  ExportOptions,
  ExportProgress,
  ExportSummary,
  GalleryShot,
  Clip,
  ClipState,
  ClipUsage,
  FfmpegStatus,
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
    /** Rückfall-Meldung (bei Fehlern aus dem Kern: deutsch, fürs Log). */
    message: string,
    /** Übersetzungs-Code: `errors.<code>` in `app/locales/*.json`. */
    public readonly code?: string,
    public readonly params?: Record<string, string>,
    /** Fehlercode der TRS API (z. B. `cape_locked`). */
    public readonly apiCode?: string,
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
    throw new BackendError('no_backend', 'Das Backend ist nur in der Desktop-App verfügbar.', 'noBackend')
  }
  try {
    return await invoke<T>(command, args)
  } catch (e) {
    if (isCommandError(e)) {
      throw new BackendError(
        e.kind,
        e.message,
        typeof e.code === 'string' ? e.code : undefined,
        e.params && typeof e.params === 'object' ? e.params : undefined,
        typeof e.apiCode === 'string' ? e.apiCode : undefined,
      )
    }
    throw new BackendError('unknown', 'Ein unerwarteter Fehler ist aufgetreten.', 'unexpected')
  }
}

/** Wie `call`, prüft die Antwort aber mit einem zod-Schema (TRS-Daten). */
async function checked<S extends z.ZodType>(schema: S, command: string, args?: Record<string, unknown>): Promise<z.output<S>> {
  return trsParse(schema, await call<unknown>(command, args))
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
   * `joinAddress`: freie Adresse (z. B. der geteilte Server eines Freundes); prüft der Kern.
   */
  launchInstance: (
    id: string,
    joinServer: string | null,
    onProgress: (p: StageProgress) => void,
    taskId: string | null = null,
    joinAddress: string | null = null,
  ) => call<number>('launch_instance', { id, joinServer, joinAddress, onProgress: channel(onProgress), taskId }),
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
      platform: update.platform ?? null,
      taskId,
    }),
  /** Öffnet z. B. den Mods-Ordner der Instanz im Explorer. */
  openContentDir: (id: string, kind: ContentKind) => call<void>('open_content_dir', { id, kind }),
  installPerformancePack: (id: string, taskId: string | null = null) =>
    call<string[]>('install_performance_pack', { id, taskId }),
  /** Icons, Titel, Autoren von Modrinth nachladen; `true` = Liste neu laden. */
  refreshContentMeta: (id: string) => call<boolean>('refresh_content_meta', { id }),
  /** Neuere passende Versionen seit der installierten, mit Changelog. */
  contentChangelog: (
    id: string,
    projectId: string,
    kind: ContentKind,
    installedVersionId: string,
    platform: Platform = 'modrinth',
  ) => call<ModrinthVersion[]>('content_changelog', { id, projectId, kind, installedVersionId, platform }),
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

  /**
   * CurseForge – alle Aufrufe laufen im Kern (der API-Schlüssel bleibt dort).
   * Die Antworten haben dieselbe Form wie bei Modrinth; IDs sind Zahlen als Text.
   */
  curseforge: {
    /** Hat dieser Build einen API-Schlüssel? Sonst CurseForge ausblenden. */
    status: () => call<{ available: boolean }>('curseforge_status'),
    search: (params: ModrinthSearchParams) => call<ModrinthSearchResult>('curseforge_search', { params }),
    categories: () => call<CategoryTag[]>('curseforge_categories'),
    project: (projectId: string) => call<ProjectDetails>('curseforge_project', { projectId }),
    projectVersions: (projectId: string) => call<ModrinthVersion[]>('curseforge_project_versions', { projectId }),
    projects: (ids: string[]) => call<ProjectCard[]>('curseforge_projects', { ids }),
    versions: (id: string, projectId: string, kind: ContentKind) =>
      call<ModrinthVersion[]>('curseforge_versions', { id, projectId, kind }),
    /** Changelog einer Datei – HTML, nur über MarkdownView (`html`) anzeigen. */
    changelog: (projectId: string, fileId: string) => call<string>('curseforge_changelog', { projectId, fileId }),
    /** Gesperrte Dateien kommen als `blocked` zurück (von Hand laden). */
    install: (id: string, projectId: string, kind: ContentKind, fileId: string | null = null, taskId: string | null = null) =>
      call<CurseForgeInstallOutcome>('curseforge_install', { id, projectId, kind, fileId, taskId }),
    installModpack: (
      projectId: string,
      onProgress: (p: PackProgress) => void,
      taskId: string | null = null,
      fileId: string | null = null,
    ) => call<CurseForgePackResult>('install_curseforge_modpack', { projectId, fileId, onProgress: channel(onProgress), taskId }),
    /** Dateien, die der Nutzer für die Instanz selbst laden muss. */
    blocked: (id: string) => call<BlockedFile[]>('curseforge_blocked', { id }),
    /** Übernimmt passende Dateien aus dem Download-Ordner (Name + SHA1). */
    adoptDownloads: (id: string) => call<AdoptResult>('curseforge_adopt_downloads', { id }),
    /** `fileId: null` = alle verwerfen. */
    dismissBlocked: (id: string, fileId: string | null) => call<BlockedFile[]>('curseforge_dismiss_blocked', { id, fileId }),
  },
  listPresets: () => call<Preset[]>('list_presets'),
  createPreset: (preset: PresetInput) => call<Preset>('create_preset', { preset }),
  updatePreset: (id: string, preset: PresetInput) => call<Preset>('update_preset', { id, preset }),
  /** „Immer automatisch“ – auch für fertige TRS-Presets. */
  setPresetAuto: (id: string, auto: boolean) => call<Preset>('set_preset_auto', { id, auto }),
  deletePreset: (id: string) => call<void>('delete_preset', { id }),
  reorderPresets: (ids: string[]) => call<Preset[]>('reorder_presets', { ids }),
  /** Fragt nach dem Speicherort; `false` = abgebrochen. */
  exportPreset: (id: string) => call<boolean>('export_preset', { id }),
  /** Öffnet eine Preset-Datei und legt ein neues Preset an; `null` = abgebrochen. */
  importPreset: () => call<Preset | null>('import_preset'),
  /** Installiert Presets in die Instanz – nur, was für Version + Loader passt. */
  applyPresets: (
    id: string,
    presetIds: string[],
    onProgress: (p: PresetProgress) => void,
    taskId: string | null = null,
  ) => call<PresetApplyReport>('apply_presets', { id, presetIds, onProgress: channel(onProgress), taskId }),
  /** Instanz mit Modloader ohne Sodium/Embeddium/OptiFine (kein Modpack) → „FPS-Boost anwenden“ anbieten. */
  fpsBoostSuggested: (id: string) => call<boolean>('fps_boost_suggested', { id }),

  listServers: () => call<Server[]>('list_servers'),
  addServer: (server: ServerInput) => call<Server>('add_server', { server }),
  updateServer: (id: string, server: ServerInput) => call<Server>('update_server', { id, server }),
  removeServer: (id: string) => call<void>('remove_server', { id }),
  pingServer: (id: string) => call<ServerStatus>('ping_server', { id }),

  /** Profil des aktiven Accounts (Skin, Modell, Umhänge) – Texturen als Data-URL. */
  skinProfile: () => call<SkinProfile>('skin_profile'),
  /** Skin-Link eines anderen Spielers (nur textures.minecraft.net), `null` = Standard-Skin. */
  playerSkinUrl: (uuid: string) => call<string | null>('player_skin_url', { uuid }),
  skinLibrary: () => call<LibrarySkin[]>('skin_library'),
  /** Öffnet den Dateidialog für ein 64×64-PNG; `null` = abgebrochen. */
  addSkinFile: (name: string, variant: SkinVariant) => call<LibrarySkin | null>('add_skin_file', { name, variant }),
  saveActiveSkin: (name: string) => call<LibrarySkin>('save_active_skin', { name }),
  deleteSkin: (id: string) => call<void>('delete_skin', { id }),
  renameSkin: (id: string, name: string) => call<LibrarySkin>('rename_skin', { id, name }),
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

  // --- Clips & Aufnahme ---
  /** Alle Clips aller Instanzen, neueste zuerst. */
  listClips: () => call<Clip[]>('list_clips'),
  clipUsage: () => call<ClipUsage>('clip_usage'),
  /** Gibt genau dieses Video fürs Abspielen frei (Pfad fürs Asset-Protokoll). */
  clipVideo: (id: string, fileName: string) => call<string | null>('clip_video', { id, fileName }),
  clipThumbnail: (id: string, fileName: string) => call<string | null>('clip_thumbnail', { id, fileName }),
  renameClip: (id: string, fileName: string, newName: string) => call<string>('rename_clip', { id, fileName, newName }),
  trashClip: (id: string, fileName: string) => call<void>('trash_clip', { id, fileName }),
  revealClip: (id: string, fileName: string) => call<void>('reveal_clip', { id, fileName }),
  openClipsFolder: () => call<void>('open_clips_folder'),
  clipStates: () => call<ClipState[]>('clip_states'),
  /** Wie die Tasten im Spiel: Clip speichern bzw. Aufnahme starten/stoppen. */
  clipAction: (id: string, record: boolean) => call<void>('clip_action', { id, record }),
  ffmpegStatus: () => call<FfmpegStatus>('ffmpeg_status'),
  installFfmpeg: (onProgress: (percent: number) => void, taskId: string | null = null) =>
    call<void>('install_ffmpeg', { onProgress: channel(onProgress), taskId }),
  pickClipsFolder: () => call<string | null>('pick_clips_folder'),

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

  /** TRS-Dienste (Umhänge, Freunde, Verwaltung). Der Token bleibt im Kern. */
  trs: {
    status: () => checked(trsStatusSchema, 'trs_status'),
    /** Stand der Synchronisation mit dem TRS-Konto (Skins, Presets, Theme/Sprache). */
    syncStatus: () => checked(trsSyncStatusSchema, 'trs_sync_status'),
    setConsent: (accepted: boolean) => checked(trsStatusSchema, 'trs_set_consent', { accepted }),
    me: () => checked(trsMeSchema, 'trs_me'),
    updateMe: (patch: Partial<TrsPrivacy>) => checked(trsMeSchema, 'trs_update_me', { patch }),
    /** Löscht alle TRS-Daten des aktiven Accounts und schaltet die Dienste aus. */
    deleteMe: () => checked(trsStatusSchema, 'trs_delete_me'),
    capes: () => checked(z.array(trsCapeSchema), 'trs_capes'),
    setCape: (capeId: string | null) => checked(z.string().nullable(), 'trs_set_cape', { capeId }),
    /** Öffnet den Dateidialog im Kern; `null` = abgebrochen. */
    uploadCape: (name: string | null) => checked(trsCapeSchema.nullable(), 'trs_upload_cape', { name }),
    deleteCape: (id: string) => call<void>('trs_delete_cape', { id }),
    reportCape: (id: string, reason: TrsReportReason, note: string | null) =>
      call<void>('trs_report_cape', { id, reason, note }),
    redeem: (code: string) => checked(trsRedeemSchema, 'trs_redeem', { code }),
    playerCapes: (uuids: string[]) => checked(z.array(trsPlayerCapeSchema), 'trs_player_capes', { uuids }),
    friends: () => checked(trsFriendsSchema, 'trs_friends'),
    blocks: () => checked(z.array(trsBlockedSchema), 'trs_blocks'),
    friendRequest: (target: string) => checked(trsFriendRequestResultSchema, 'trs_friend_request', { target }),
    acceptFriend: (uuid: string) => checked(trsFriendSchema, 'trs_friend_accept', { uuid }),
    declineFriend: (uuid: string) => call<void>('trs_friend_decline', { uuid }),
    cancelRequest: (uuid: string) => call<void>('trs_friend_cancel', { uuid }),
    removeFriend: (uuid: string) => call<void>('trs_friend_remove', { uuid }),
    block: (target: string) => checked(trsUserRefSchema, 'trs_block', { target }),
    unblock: (uuid: string) => call<void>('trs_unblock', { uuid }),
    /** Anmeldung auf der Website bestätigen – der Kern schickt den Code mit dem TRS-Token. */
    webLoginApprove: (code: string) => call<void>('trs_web_login_approve', { code }),

    adminStats: () => checked(trsAdminStatsSchema, 'trs_admin_stats'),
    adminCapes: (list: TrsReviewList) => checked(z.array(trsAdminCapeSchema), 'trs_admin_capes', { list }),
    adminApprove: (id: string) => call<void>('trs_admin_approve', { id }),
    adminReject: (id: string, reason: string | null) => call<void>('trs_admin_reject', { id, reason }),
    adminDeleteCape: (id: string) => call<void>('trs_admin_delete_cape', { id }),
    adminCodes: () => checked(z.array(trsCodeSchema), 'trs_admin_codes'),
    /** Die Klartext-Codes gibt es nur in dieser Antwort. */
    adminCreateCodes: (request: { capeId: string; maxUses: number; count: number; expiresAt?: string; note?: string }) =>
      checked(z.array(trsCodeSchema), 'trs_admin_create_codes', { request }),
    adminRevokeCode: (id: number) => call<void>('trs_admin_revoke_code', { id }),
    adminUser: (query: string) => checked(trsAdminUserSchema, 'trs_admin_user', { query }),
    /** `true` = hatte den Umhang schon. */
    adminGrant: (player: string, capeId: string) => checked(z.boolean(), 'trs_admin_grant', { player, capeId }),
    adminRevokeGrant: (uuid: string, capeId: string) => call<void>('trs_admin_revoke_grant', { uuid, capeId }),
    adminBan: (player: string, reason: string | null) => checked(trsAdminUserSchema, 'trs_admin_ban', { player, reason }),
    adminUnban: (uuid: string) => call<void>('trs_admin_unban', { uuid }),
  },
}

export function isCancelled(e: unknown): boolean {
  return e instanceof BackendError && e.kind === 'cancelled'
}

/**
 * Fehler in der eingestellten Sprache: Code vom Kern → `errors.<code>`,
 * sonst die mitgelieferte Meldung.
 */
export function errorMessage(e: unknown): string {
  if (e instanceof BackendError) return userErrorText(e)
  return t('errors.unexpected')
}

/** Übersetzt einen Fehler, der als Daten ankommt (z. B. `UploadResult.error`). */
export function userErrorText(error: { code?: string | null; params?: Record<string, string> | null; message: string }): string {
  if (error.code && hasKey(`errors.${error.code}`)) return tKey(`errors.${error.code}`, error.params ?? undefined)
  return error.message || t('errors.unexpected')
}
