import type { AsyncData, NuxtError } from '#app'

// useFetch mit ausdrücklichem Antworttyp, aber OHNE Nitros Routen-Typen: Nuxt rechnet bei jedem useFetch-Aufruf die
// Methoden aller API-Routen durch (`NitroFetchRequest`, ~200 Pfade). Ab einer gewissen Zahl Routen bricht TypeScript
// dabei ab (TS2589 „excessively deep“) und die Seiten verlieren still ihre Typen. Zur Laufzeit ist es genau useFetch.
// Den `key` immer angeben – Nuxts automatischer Schlüssel hängt am Aufruf-Ort und wäre hier für alle Aufrufe gleich.

interface ApiFetchOptions<T> {
  key: string
  default?: () => T
  server?: boolean
  lazy?: boolean
  immediate?: boolean
  /** z. B. `accept`, damit `/v1/events` nicht den alten SSE-Stream ausliefert. */
  headers?: Record<string, string>
}

type RawUseFetch = (request: string | (() => string), opts: ApiFetchOptions<unknown>) => unknown

/** Optionen für {@link apiFetch} (Teilmenge von ofetch). */
export interface ApiRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
  credentials?: RequestCredentials
  query?: Record<string, string | number>
}

/** `$fetch` mit ausdrücklichem Antworttyp, ohne Nitros Routen-Typen (siehe oben). */
export function apiFetch<T = unknown>(url: string, opts: ApiRequestOptions = {}): Promise<T> {
  return ($fetch as unknown as (u: string, o: ApiRequestOptions) => Promise<T>)(url, opts)
}

export function useApiFetch<T>(request: string | (() => string), opts: ApiFetchOptions<T> & { default: () => T }): AsyncData<T, NuxtError | undefined>
export function useApiFetch<T>(request: string | (() => string), opts: ApiFetchOptions<T>): AsyncData<T | undefined, NuxtError | undefined>
export function useApiFetch<T>(request: string | (() => string), opts: ApiFetchOptions<T>): AsyncData<T | undefined, NuxtError | undefined> {
  return (useFetch as unknown as RawUseFetch)(request, opts as ApiFetchOptions<unknown>) as AsyncData<T | undefined, NuxtError | undefined>
}
