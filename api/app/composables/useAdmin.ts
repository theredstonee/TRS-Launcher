// Team-Bereich: Sitzung über das httpOnly-Cookie (gesetzt von /v1/web-login/poll); ändernde
// Anfragen schicken das CSRF-Token im Header X-CSRF-Token mit. Rolle, Rechte und Grenzen kommen
// von /v1/web-login/me – die Oberfläche blendet danach ein/aus, geprüft wird immer serverseitig.

export type StaffRole = 'admin' | 'moderator'

export interface StaffLimits {
  kinds: string[]
  maxMinutes: number | null
  maxWarnMinutes: number | null
  permanent: boolean
}

export interface AdminSession {
  name: string
  uuid?: string
  csrf: string
  role: StaffRole
  permissions: string[]
  limits: StaffLimits
}

export interface ApiErrorBody {
  error?: { code?: string, message?: string }
}

/** Lesbare Meldung aus einem $fetch-Fehler (API-Format `{ error: { code, message } }`). */
export function apiMessage(e: unknown): string {
  const data = (e as { data?: ApiErrorBody }).data
  return data?.error?.message ?? data?.error?.code ?? (e as Error).message ?? 'error'
}

/** Stabiler Fehlercode aus einem $fetch-Fehler (oder `''`). */
export function apiCode(e: unknown): string {
  return (e as { data?: ApiErrorBody }).data?.error?.code ?? ''
}

/** Zähler für die Seitenleiste (aus dem Dashboard). */
export interface AdminCounts {
  reports: number
  highPriority: number
  appeals: number
  uploads: number
}

export function useAdmin() {
  const session = useState<AdminSession | null>('admin-session', () => null)
  const counts = useState<AdminCounts | null>('admin-counts', () => null)

  async function load(): Promise<boolean> {
    try {
      session.value = await $fetch<AdminSession>('/v1/web-login/me', { credentials: 'same-origin' })
      return true
    } catch {
      session.value = null
      return false
    }
  }

  function api<T>(path: string, opts: { method?: 'GET' | 'POST' | 'DELETE' | 'PATCH' | 'PUT', body?: unknown } = {}): Promise<T> {
    const method = opts.method ?? 'GET'
    const headers: Record<string, string> = {}
    if (method !== 'GET' && session.value) headers['X-CSRF-Token'] = session.value.csrf
    return $fetch<T>(path, { method, body: opts.body as Record<string, unknown> | undefined, headers, credentials: 'same-origin' }) as Promise<T>
  }

  async function logout() {
    try {
      await api('/v1/web-login/logout', { method: 'POST' })
    } finally {
      session.value = null
    }
  }

  /** Recht aus der Sitzung (nur für die Anzeige). */
  function can(permission: string): boolean {
    return session.value?.permissions.includes(permission) ?? false
  }

  const isAdmin = computed(() => session.value?.role === 'admin')

  return { session, counts, load, api, logout, can, isAdmin }
}
