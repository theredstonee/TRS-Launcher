import { Channel, invoke, isTauri } from '@tauri-apps/api/core'
import type {
  Account,
  AppInfo,
  DeviceCode,
  LogLine,
  RunningGame,
  StageProgress,
  CommandError,
  Instance,
  InstanceOverrides,
  NewInstance,
  Settings,
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
  openInstanceDir: (id: string) => call<void>('open_instance_dir', { id }),

  getVersionManifest: (forceRefresh = false) =>
    call<VersionManifest>('get_version_manifest', { forceRefresh }),

  /** Löst erst auf, wenn das Spiel gestartet ist; Fortschritt kommt über `onProgress`. */
  launchInstance: (id: string, onProgress: (p: StageProgress) => void) => {
    const channel = new Channel<StageProgress>()
    channel.onmessage = onProgress
    return call<number>('launch_instance', { id, onProgress: channel })
  },
  stopInstance: (id: string) => call<boolean>('stop_instance', { id }),
  runningGames: () => call<RunningGame[]>('running_games'),
  getGameLogs: (id: string) => call<LogLine[]>('get_game_logs', { id }),

  listAccounts: () => call<Account[]>('list_accounts'),
  loginBrowser: () => call<Account>('login_browser'),
  loginDeviceCode: (onCode: (code: DeviceCode) => void) => {
    const channel = new Channel<DeviceCode>()
    channel.onmessage = onCode
    return call<Account>('login_device_code', { onCode: channel })
  },
  cancelLogin: () => call<void>('cancel_login'),
  setActiveAccount: (id: string) => call<void>('set_active_account', { id }),
  removeAccount: (id: string) => call<void>('remove_account', { id }),
}

export function isCancelled(e: unknown): boolean {
  return e instanceof BackendError && e.kind === 'cancelled'
}

export function errorMessage(e: unknown): string {
  return e instanceof BackendError ? e.message : 'Ein unerwarteter Fehler ist aufgetreten.'
}
