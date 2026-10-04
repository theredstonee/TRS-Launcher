import { createHash, createPublicKey, verify } from 'node:crypto'
import { z } from 'zod'
import { cached } from './site'

// Handy-Apps für die Download-Seite: das Manifest des GitHub-Release-Kanals `mobile` (mobile.json + mobile.json.sig,
// geschrieben von scripts/mobile-channel.mjs im Launcher-Repo). Die Website prüft die Minisign-Signatur genauso wie
// die App (Prehash, `file:mobile.json` im signierten Kommentar, Schlüssel aus tauri.conf.json) und gibt nur geprüfte,
// streng gefilterte Felder weiter – nie Text aus dem Manifest (Release-Notizen bleiben draußen).

export const MOBILE_CHANNEL = 'https://github.com/theredstonee/TRS-Launcher/releases/download/mobile/'
export const MOBILE_RELEASE_PAGE = 'https://github.com/theredstonee/TRS-Launcher/releases/tag/mobile'
/** Fest: die AltStore-/SideStore-Quelle hat immer dieselbe Adresse. */
export const ALTSTORE_SOURCE = `${MOBILE_CHANNEL}altstore.json`

/** Öffentlicher Update-Schlüssel des Launchers (`plugins.updater.pubkey` in src-tauri/tauri.conf.json). */
export const MOBILE_PUBLIC_KEY =
  'dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IDQxOEQ3OUEwNjk2NTQxRjkKUldUNVFXVnBvSG1OUVRQZk5iYnpLcVdXbndGbVhxbDZaa1F2VnYrNm1rUzVzTG1NTmcyQWpiYVkK'

const MANIFEST = 'mobile.json'
const MAX_MANIFEST_BYTES = 256 * 1024
const MAX_SIGNATURE_BYTES = 4096
const MAX_FILE_BYTES = 2 * 1024 * 1024 * 1024

export interface MobileFile {
  url: string
  name: string
  sha256: string
  size: number
}

export interface MobileLatest {
  version: string
  publishedAt: string | null
  android: MobileFile | null
  ios: (MobileFile & { altstore: string }) | null
}

/** Datei im Kanal: nur `https://github.com/…/releases/download/mobile/<name>.<ext>`, harmloser Dateiname. */
function channelFile(ext: 'apk' | 'ipa' | 'json') {
  const name = new RegExp(`^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.${ext}$`)
  return z.string().max(300).refine((u) => u.startsWith(MOBILE_CHANNEL) && name.test(u.slice(MOBILE_CHANNEL.length)))
}

const file = (ext: 'apk' | 'ipa') =>
  z.object({
    url: channelFile(ext),
    sha256: z.string().regex(/^[0-9a-fA-F]{64}$/),
    size: z.number().int().positive().max(MAX_FILE_BYTES),
  })

const manifestSchema = z.object({
  version: z.string().max(40).regex(/^\d{1,9}\.\d{1,9}\.\d{1,9}(?:-[0-9A-Za-z.]{1,20})?$/),
  pubDate: z.string().max(64).optional().nullable(),
  // Ein kaputter Teil blendet nur diese Plattform aus, nicht beide.
  android: file('apk').nullable().optional().catch(null),
  ios: file('ipa').extend({ altstore: channelFile('json').optional().nullable() }).nullable().optional().catch(null),
})

function lines(base64: string): string[] | null {
  const text = Buffer.from(base64.trim(), 'base64').toString('utf8')
  return text ? text.split(/\r?\n/) : null
}

/**
 * Minisign-Prüfung wie in der App (`minisign_verify`, nur Prehash „ED“): Signatur über BLAKE2b-512 der Daten, globale
 * Signatur über Signatur + signierten Kommentar, Kommentar muss `file:<name>` enthalten.
 */
export function verifyMinisign(publicKey: string, data: Uint8Array, signature: string, fileName: string): boolean {
  try {
    const pubLines = lines(publicKey)
    const sigLines = lines(signature)
    if (!pubLines?.[1] || !sigLines?.[1] || !sigLines[2]?.startsWith('trusted comment: ') || !sigLines[3]) return false
    const pub = Buffer.from(pubLines[1], 'base64')
    const sig = Buffer.from(sigLines[1], 'base64')
    const trusted = sigLines[2].slice('trusted comment: '.length)
    const globalSig = Buffer.from(sigLines[3], 'base64')
    if (pub.length !== 42 || sig.length !== 74 || globalSig.length !== 64) return false
    if (pub.subarray(0, 2).toString() !== 'Ed' || sig.subarray(0, 2).toString() !== 'ED') return false
    if (!sig.subarray(2, 10).equals(pub.subarray(2, 10))) return false
    const key = createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), pub.subarray(10)]), format: 'der', type: 'spki' })
    const digest = createHash('blake2b512').update(data).digest()
    const ok =
      verify(null, digest, key, sig.subarray(10)) &&
      verify(null, Buffer.concat([sig.subarray(10), Buffer.from(trusted, 'utf8')]), key, globalSig)
    return ok && trusted.split('\t').includes(`file:${fileName}`)
  } catch {
    return false
  }
}

/** Geprüftes Manifest → Felder für die Website, sonst `null` (falsche Signatur, unlesbar, keine Datei). */
export function parseMobileManifest(data: Uint8Array, signature: string, publicKey = MOBILE_PUBLIC_KEY): MobileLatest | null {
  if (data.byteLength > MAX_MANIFEST_BYTES || signature.length > MAX_SIGNATURE_BYTES) return null
  if (!verifyMinisign(publicKey, data, signature, MANIFEST)) return null
  let json: unknown
  try {
    json = JSON.parse(Buffer.from(data).toString('utf8'))
  } catch {
    return null
  }
  const parsed = manifestSchema.safeParse(json)
  if (!parsed.success) return null
  const m = parsed.data
  const view = (f: { url: string, sha256: string, size: number }): MobileFile => ({
    url: f.url,
    name: f.url.slice(MOBILE_CHANNEL.length),
    sha256: f.sha256.toLowerCase(),
    size: f.size,
  })
  const android = m.android ? view(m.android) : null
  const ios = m.ios ? { ...view(m.ios), altstore: m.ios.altstore ?? ALTSTORE_SOURCE } : null
  if (!android && !ios) return null
  const published = m.pubDate && !Number.isNaN(Date.parse(m.pubDate)) ? new Date(m.pubDate).toISOString() : null
  return { version: m.version, publishedAt: published, android, ios }
}

async function fetchLimited(url: string, max: number): Promise<Uint8Array> {
  const res = await fetch(url, {
    headers: { 'User-Agent': 'trs-launcher-website' },
    signal: AbortSignal.timeout(10_000),
    redirect: 'follow',
  })
  if (!res.ok) throw new Error(`${url} → HTTP ${res.status}`)
  const length = Number(res.headers.get('content-length') ?? 0)
  if (length > max) throw new Error(`${url} → zu groß`)
  const bytes = new Uint8Array(await res.arrayBuffer())
  if (bytes.byteLength > max) throw new Error(`${url} → zu groß`)
  return bytes
}

/** Neueste Handy-Version aus dem Kanal (10 min zwischengespeichert, bei Fehlern der letzte gute Stand). */
export function latestMobile(): Promise<MobileLatest | null> {
  return cached('mobile', async () => {
    const [data, sig] = await Promise.all([
      fetchLimited(`${MOBILE_CHANNEL}${MANIFEST}`, MAX_MANIFEST_BYTES),
      fetchLimited(`${MOBILE_CHANNEL}${MANIFEST}.sig`, MAX_SIGNATURE_BYTES),
    ])
    const latest = parseMobileManifest(data, Buffer.from(sig).toString('utf8'))
    // Ungültig = Fehler: der Zwischenspeicher behält dann den letzten geprüften Stand.
    if (!latest) throw new Error('mobile.json: Signatur oder Inhalt ungültig')
    return latest
  })
}
