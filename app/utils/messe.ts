// Messe-Build („TRS Launcher Messe“): Präsentation im Leerlauf, Rückkehr nach Untätigkeit, Personal-PIN und
// gesperrte Aktionen für Besucher. Nur aktiv, wenn beim Bauen TRS_MESSE=1 gesetzt war (nuxt.config.ts) –
// in normalen Builds ist MESSE fest `false` und alles hier fällt beim Bündeln weg.
// Relativ importiert (kein Nuxt-Kontext), damit tests/messe.test.ts die Logik direkt laden kann.
import { computed, ref } from 'vue'

/** Messe-Build? Wird zur Bauzeit fest eingesetzt. */
export const MESSE: boolean = import.meta.env.VITE_TRS_MESSE === '1'

/** Personal hat die Sperren mit der PIN aufgehoben (bis zur nächsten Präsentation). */
export const messeStaffUnlocked = ref(false)

/** Besucher-Sperren aktiv: Messe-Build und kein Personal-Modus. */
export const messeRestricted = computed(() => MESSE && !messeStaffUnlocked.value)

/** Kiosk-Ebene nur im Hauptfenster (nicht im REC-Overlay oder auf Vorschau-Seiten). */
export function messeKioskWindow(): boolean {
  if (typeof window === 'undefined') return false
  const path = window.location.pathname
  if (path.startsWith('/clip-overlay') || path.startsWith('/intro-preview')) return false
  const label = (window as { __TAURI_INTERNALS__?: { metadata?: { currentWindow?: { label?: string } } } }).__TAURI_INTERNALS__?.metadata?.currentWindow?.label
  return !label || label === 'main'
}

// ---------------------------------------------------------------------------------------------------------------
// Gesperrte Kern-Befehle (letzte Hürde hinter den ausgeblendeten Knöpfen)

/**
 * Befehle, die Besucher am Messe-PC nicht auslösen dürfen: Löschen, Abmelden, Daten ausführen/hochladen,
 * Ordner/Browser öffnen (raus aus der App), Datenordner umziehen.
 */
const BLOCKED_COMMANDS = new Set([
  // Konten und TRS-Konto (Anmelden nur im Personal-Modus – Besucher-Konten blieben sonst am Stand gespeichert)
  'login_browser',
  'login_device_code',
  'remove_account',
  'trs_delete_me',
  'trs_set_consent',
  'trs_web_login_decide',
  'trs_friend_remove',
  // Instanzen, Welten, Speicher, Datenordner
  'delete_instance',
  'move_instance',
  'trash_world',
  'clean_unused_storage',
  'data_move_plan',
  'confirm_data_move',
  // Daten nach draußen
  'share_log',
  'share_log_source',
  'export_modpack',
  'export_server',
  'export_preset',
  'export_clip',
  'upload_shared_pack',
  'pack_delete',
  'controls_export_file',
  // Ordner, Dateien und Browser öffnen
  'open_data_dir',
  'open_instance_dir',
  'open_content_dir',
  'open_external_url',
  'open_instance_file',
  'open_world_folder',
  'open_screenshot',
  'open_clip_external',
  'open_clips_folder',
  'local_server_open_folder',
  'reveal_clip',
  'reveal_instance_file',
  'reveal_screenshot',
])

/** Team-Bereich (Moderation, Admin) ist am Messe-PC ganz gesperrt. */
const BLOCKED_PREFIXES = ['trs_admin_', 'admin_']

export function isMesseBlockedCommand(command: string): boolean {
  return BLOCKED_COMMANDS.has(command) || BLOCKED_PREFIXES.some((p) => command.startsWith(p))
}

// ---------------------------------------------------------------------------------------------------------------
// Präsentation (iframe) – Nachrichten nur aus dem eigenen Rahmen annehmen

export interface PitchMessageEvent {
  source: unknown
  data: unknown
}

/** „Jetzt selbst ausprobieren“ aus der Präsentation? Nur vom eigenen iframe und mit passender Kennung. */
export function isPitchDemoMessage(event: PitchMessageEvent, frame: unknown): boolean {
  if (!frame || event.source !== frame) return false
  const data = event.data
  if (typeof data !== 'object' || data === null) return false
  const d = data as Record<string, unknown>
  return d.source === 'trs-pitch' && d.type === 'demo'
}

export type MesseLang = 'de' | 'en'

/** Adresse der eingebauten Präsentation (Endlosschleife mit „Jetzt selbst ausprobieren“). */
export function pitchUrl(lang: MesseLang): string {
  return `/messe-pitch/index.html?mode=loop&demo=1&lang=${lang}`
}

// ---------------------------------------------------------------------------------------------------------------
// Leerlauf: nach `timeoutMs` ohne Eingabe Countdown, danach zurück zur Präsentation

export const COUNTDOWN_MS = 10_000
export const MIN_TIMEOUT_S = 20
export const MAX_TIMEOUT_S = 1800
export const DEFAULT_TIMEOUT_S = 90

export type IdlePhase = { kind: 'active' } | { kind: 'countdown'; secondsLeft: number } | { kind: 'idle' }

/**
 * Zustand nach der letzten Eingabe. Der Countdown läuft in den letzten `countdownMs` vor Ablauf
 * (Gesamtzeit bis zur Präsentation = timeoutMs + countdownMs).
 */
export function idlePhase(lastInputMs: number, nowMs: number, timeoutMs: number, countdownMs = COUNTDOWN_MS): IdlePhase {
  const quiet = Math.max(0, nowMs - lastInputMs)
  if (quiet < timeoutMs) return { kind: 'active' }
  const left = timeoutMs + countdownMs - quiet
  if (left > 0) return { kind: 'countdown', secondsLeft: Math.ceil(left / 1000) }
  return { kind: 'idle' }
}

export interface IdleWatchOptions {
  timeoutMs: () => number
  /** Gerade nicht zurückkehren (Spiel läuft, Personal-Panel offen …) – die Uhr läuft dann neu an. */
  blocked: () => boolean
  onPhase: (phase: IdlePhase) => void
  onIdle: () => void
  now?: () => number
}

/** Uhr für den Leerlauf: `input()` bei jeder Eingabe, `tick()` regelmäßig (z. B. alle 250 ms). */
export class IdleWatch {
  private last: number
  private phase: IdlePhase['kind'] = 'active'
  private readonly now: () => number

  constructor(private readonly opts: IdleWatchOptions) {
    this.now = opts.now ?? (() => Date.now())
    this.last = this.now()
  }

  /** Eingabe: Uhr zurücksetzen (bricht auch einen laufenden Countdown ab). */
  input() {
    this.last = this.now()
    if (this.phase !== 'active') {
      this.phase = 'active'
      this.opts.onPhase({ kind: 'active' })
    }
  }

  tick() {
    if (this.opts.blocked()) return this.input()
    const p = idlePhase(this.last, this.now(), this.opts.timeoutMs())
    if (p.kind === 'idle') {
      this.phase = 'active'
      this.last = this.now()
      this.opts.onPhase({ kind: 'active' })
      this.opts.onIdle()
      return
    }
    if (p.kind === 'countdown' || p.kind !== this.phase) this.opts.onPhase(p)
    this.phase = p.kind
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Einstellungen des Messe-PCs (lokal im WebView-Speicher des Messe-Builds)

export interface MesseConfig {
  timeoutS: number
  lang: MesseLang
}

const CONFIG_KEY = 'trs.messe.config'
const PIN_KEY = 'trs.messe.pin'

export function clampTimeout(seconds: unknown): number {
  const n = typeof seconds === 'number' ? seconds : Number(seconds)
  if (!Number.isFinite(n)) return DEFAULT_TIMEOUT_S
  return Math.min(MAX_TIMEOUT_S, Math.max(MIN_TIMEOUT_S, Math.round(n)))
}

export function parseMesseConfig(raw: string | null): MesseConfig {
  let value: Partial<MesseConfig> = {}
  try {
    const parsed: unknown = raw ? JSON.parse(raw) : null
    if (parsed && typeof parsed === 'object') value = parsed as Partial<MesseConfig>
  } catch {
    // kaputt → Standard
  }
  return { timeoutS: clampTimeout(value.timeoutS ?? DEFAULT_TIMEOUT_S), lang: value.lang === 'en' ? 'en' : 'de' }
}

export function readMesseConfig(): MesseConfig {
  try {
    return parseMesseConfig(localStorage.getItem(CONFIG_KEY))
  } catch {
    return parseMesseConfig(null)
  }
}

export function writeMesseConfig(config: MesseConfig) {
  try {
    localStorage.setItem(CONFIG_KEY, JSON.stringify({ timeoutS: clampTimeout(config.timeoutS), lang: config.lang }))
  } catch {
    // Speicher gesperrt – gilt dann nur bis zum Neustart
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Personal-PIN: 4–8 Ziffern, gespeichert nur als gesalzener SHA-256

export interface PinRecord {
  salt: string
  hash: string
  /** Hash der eingebauten PIN, als diese PIN am Stand gesetzt wurde (neue EXE mit neuer PIN gewinnt). */
  base?: string
}

export function isValidPin(pin: string): boolean {
  return /^\d{4,8}$/.test(pin)
}

function hex(bytes: ArrayBuffer | Uint8Array): string {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export async function hashPin(pin: string, salt: string): Promise<string> {
  const data = new TextEncoder().encode(`trs-messe:${salt}:${pin}`)
  return hex(await crypto.subtle.digest('SHA-256', data))
}

export async function createPinRecord(pin: string): Promise<PinRecord> {
  const salt = hex(crypto.getRandomValues(new Uint8Array(16)))
  return { salt, hash: await hashPin(pin, salt) }
}

export async function verifyPin(pin: string, record: PinRecord): Promise<boolean> {
  if (!isValidPin(pin)) return false
  return (await hashPin(pin, record.salt)) === record.hash
}

function asPinRecord(value: unknown): PinRecord | null {
  if (!value || typeof value !== 'object') return null
  const { salt, hash, base } = value as Record<string, unknown>
  if (typeof salt !== 'string' || typeof hash !== 'string' || !/^[0-9a-f]{64}$/.test(hash)) return null
  return typeof base === 'string' ? { salt, hash, base } : { salt, hash }
}

/** Eingebaute PIN (`build.mjs --pin`, zur Bauzeit nur als gesalzener Hash), sonst null. */
export function parseBuiltInPin(raw: unknown): PinRecord | null {
  if (typeof raw !== 'string' || !raw) return null
  try {
    const record = asPinRecord(JSON.parse(raw))
    return record && { salt: record.salt, hash: record.hash }
  } catch {
    return null
  }
}

export const BUILT_IN_PIN: PinRecord | null = parseBuiltInPin(import.meta.env.VITE_TRS_MESSE_PIN)

/** Am Stand gesetzte PIN gilt, solange sie zur eingebauten PIN dieser EXE gehört – sonst die eingebaute. */
export function pickPinRecord(stored: PinRecord | null, builtIn: PinRecord | null): PinRecord | null {
  if (!builtIn) return stored
  return stored && stored.base === builtIn.hash ? stored : builtIn
}

export function readPinRecord(): PinRecord | null {
  let stored: PinRecord | null = null
  try {
    stored = asPinRecord(JSON.parse(localStorage.getItem(PIN_KEY) ?? 'null'))
  } catch {
    // kein Speicher → keine gespeicherte PIN
  }
  return pickPinRecord(stored, BUILT_IN_PIN)
}

export function writePinRecord(record: PinRecord) {
  try {
    const base = BUILT_IN_PIN?.hash
    localStorage.setItem(PIN_KEY, JSON.stringify(base ? { salt: record.salt, hash: record.hash, base } : record))
  } catch {
    // ohne Speicher gilt die PIN nur bis zum Neustart
  }
}

/** Nach zu vielen Fehlversuchen kurz sperren (Besucher sollen nicht durchprobieren). */
export const PIN_MAX_TRIES = 5
export const PIN_LOCK_MS = 30_000
