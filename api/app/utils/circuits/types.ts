// Antworten der Schaltungs-Routen (§25), wie sie Website und Team-Bereich benutzen.
import type { CircuitCategory, CircuitData } from '#shared/circuits'

export interface CircuitAuthor {
  uuid: string
  name: string
  /** Nur auf der Detailseite: gespeicherte Skin-Adresse für den Kopf. */
  skin?: string | null
}

export type CircuitJson = CircuitData & { rev: number, updatedAt: string, author: CircuitAuthor | null }

export interface SiteCircuit {
  circuit: CircuitJson
  size: { x: number, y: number, z: number }
  blockCount: number
  materials: { key: string, item: string, count: number }[]
  minVersion: string
  maxVersion: string | null
  publishedAt: string | null
}

export type CircuitStatus = 'draft' | 'published' | 'hidden'
export type SubmissionStatus = 'pending' | 'approved' | 'rejected'

export interface AdminCircuitSummary {
  id: string
  rev: number
  status: CircuitStatus
  category: CircuitCategory
  difficulty: number
  names: Record<string, string>
  author: CircuitAuthor | null
  source: 'seed' | 'team' | 'submission'
  edited: boolean
  size: { x: number, y: number, z: number }
  blockCount: number
  minVersion: string | null
  maxVersion: string | null
  sort: number
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

export interface AdminCircuitDetail extends AdminCircuitSummary {
  circuit: CircuitJson
}

export interface MySubmission {
  id: string
  name: string
  category: CircuitCategory
  lang: string
  status: SubmissionStatus
  reason: string | null
  circuitId: string | null
  createdAt: string
  updatedAt: string
  decidedAt: string | null
}

export interface AdminSubmission extends MySubmission {
  submitter: CircuitAuthor
  description: string
  format: 'json' | 'litematic' | 'schem' | 'nbt'
  circuit: CircuitData
  size: { x: number, y: number, z: number }
  blockCount: number
  decidedBy: string | null
  submitterStats: { pending: number, approved: number, rejected: number }
}

export interface ImportWarning {
  block: string
  count: number
  action: 'solid' | 'converted' | 'skipped'
  as?: string
}

export interface ImportResult {
  format: 'json' | 'litematic' | 'schem' | 'nbt'
  circuit: CircuitData
  warnings: ImportWarning[]
  size: { x: number, y: number, z: number }
  blockCount: number
}

/** Rohe Datei an eine Umwandlungs-Route schicken (Website-Sitzung: CSRF-Header nötig). */
export async function uploadCircuitFile(url: string, file: File, csrf: string | undefined): Promise<ImportResult> {
  const res = await fetch(`${url}?name=${encodeURIComponent(file.name.slice(0, 100))}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/octet-stream', ...(csrf ? { 'X-CSRF-Token': csrf } : {}) },
    body: file,
  })
  const body = await res.json().catch(() => null) as unknown
  if (!res.ok) throw Object.assign(new Error(`HTTP ${res.status}`), { data: body, status: res.status })
  return body as ImportResult
}

/** Fehlertext einer abgelehnten Schaltung (erste Gründe aus `details.errors`). */
export function circuitErrorDetail(e: unknown): string {
  const data = (e as { data?: { error?: { errors?: string[], message?: string } } }).data
  const list = data?.error?.errors
  if (Array.isArray(list) && list.length) return list.slice(0, 3).join('; ')
  return data?.error?.message ?? ''
}

/** Datei per POST holen und im Browser speichern (Export des ungespeicherten Editor-Stands). */
export async function downloadPost(url: string, body: unknown, csrf: string | undefined, filename: string): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-CSRF-Token': csrf } : {}) },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => null) as unknown
    throw Object.assign(new Error(`HTTP ${res.status}`), { data, status: res.status })
  }
  const blob = await res.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
}

/** Leere Vorlage für eine neue Schaltung im Editor. */
export function blankCircuit(): CircuitData {
  return {
    format: 1,
    id: 'new_circuit',
    category: 'basics',
    difficulty: 1,
    server: 'ok',
    texts: {},
    palette: { A: 'lever[face=floor]@A', '-': 'redstone_wire', L: 'redstone_lamp@Q' },
    layers: [['A--L']],
  }
}
