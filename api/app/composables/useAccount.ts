// Website-Anmeldung (Microsoft §24.1 oder TRS Launcher §29): Sitzung im httpOnly-Cookie `trs_session`, ändernde Anfragen schicken das
// CSRF-Token im Header X-CSRF-Token. Wird nur im Browser geladen (keine persönlichen Daten im SSR-HTML).

export interface TeamRoleRef {
  id: string
  name: string | null
  color: string
  builtin: boolean
}

export interface StaffLimits {
  kinds: string[]
  maxMinutes: number | null
  maxWarnMinutes: number | null
  permanent: boolean
}

export interface MyTeam {
  owner: boolean
  rank: number
  roles: TeamRoleRef[]
  permissions: string[]
  limits: StaffLimits
}

export interface WebAccount {
  uuid: string
  name: string
  skin: string | null
  csrf: string
  expiresAt: string
  team: MyTeam | null
}

export function useAccount() {
  const account = useState<WebAccount | null>('web-account', () => null)
  const loaded = useState<boolean>('web-account-loaded', () => false)
  const route = useRoute()

  async function load(force = false): Promise<WebAccount | null> {
    if (loaded.value && !force) return account.value
    try {
      account.value = await apiFetch<WebAccount>('/v1/web/me', { credentials: 'same-origin' })
    } catch {
      account.value = null
    } finally {
      loaded.value = true
    }
    return account.value
  }

  function api<T>(path: string, opts: { method?: 'GET' | 'POST' | 'DELETE' | 'PATCH' | 'PUT', body?: unknown } = {}): Promise<T> {
    const method = opts.method ?? 'GET'
    const headers: Record<string, string> = {}
    if (method !== 'GET' && account.value) headers['X-CSRF-Token'] = account.value.csrf
    return apiFetch<T>(path, { method, body: opts.body, headers, credentials: 'same-origin' }) as Promise<T>
  }

  async function logout(): Promise<void> {
    try {
      await api('/v1/web/logout', { method: 'POST' })
    } finally {
      account.value = null
    }
  }

  /** Link zur Anmeldeseite (TRS Launcher oder Microsoft, §29) mit Rücksprung (Standard: aktuelle Seite). */
  function loginUrl(returnTo?: string): string {
    return `/login?return=${encodeURIComponent(returnTo ?? route.fullPath)}`
  }

  return { account, loaded, load, api, logout, loginUrl }
}
