// Admin-Bereich: Sitzung über das httpOnly-Cookie (gesetzt von /v1/web-login/poll); ändernde
// Anfragen schicken das CSRF-Token im Header X-CSRF-Token mit.

export interface AdminSession {
  name: string
  uuid?: string
  csrf: string
}

export interface ApiErrorBody {
  error?: { code?: string, message?: string }
}

/** Lesbare Meldung aus einem $fetch-Fehler (API-Format `{ error: { code, message } }`). */
export function apiMessage(e: unknown): string {
  const data = (e as { data?: ApiErrorBody }).data
  return data?.error?.message ?? data?.error?.code ?? (e as Error).message ?? 'error'
}

export function useAdmin() {
  const session = useState<AdminSession | null>('admin-session', () => null)

  async function load(): Promise<boolean> {
    try {
      session.value = await $fetch<AdminSession>('/v1/web-login/me', { credentials: 'same-origin' })
      return true
    } catch {
      session.value = null
      return false
    }
  }

  function api<T>(path: string, opts: { method?: 'GET' | 'POST' | 'DELETE' | 'PATCH', body?: unknown } = {}): Promise<T> {
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

  return { session, load, api, logout }
}
