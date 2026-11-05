import { computed } from 'vue'
import { currentLocale, hasKey, t, type Locale } from './i18n'

// Urheberhinweis des TRS Launchers. Additional terms under GPL-3.0 section 7(b) apply: see the file NOTICE.
//
// Der Text steht in den Sprachdateien (settings.about.notice); hier liegen das Original und sein SHA-256. Fehlt oder
// ändert sich der Text, zeigt der Launcher eine deutliche Warnung und trotzdem das Original. Absichtlich ohne
// Startsperre und ohne Meldung nach außen. Bei freier Software lässt sich das alles umbauen; die Prüfung macht eine
// Änderung nur sichtbar, sie verhindert sie nicht. Änderungen an Text oder Hashes: siehe AGENTS.md und NOTICE.

type NoticeLanguage = 'en' | 'de' | 'es'

const ORIGINAL: Record<NoticeLanguage, string> = {
  en: 'Original TRS Launcher by Ohev Tamerin (theredstonee). This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.',
  de: 'Original TRS Launcher von Ohev Tamerin (theredstonee). Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.',
  es: 'TRS Launcher original de Ohev Tamerin (theredstonee). Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.',
}

const SHA256: Record<NoticeLanguage, string> = {
  en: 'a6f537d2fa79ad8b33184c0aa8315334c66e7437559a6e879f19596f533c6fbb',
  de: '2ab5a26138685df0f89cfc9dab74a6378c057634cb75dfad96c9cfffcc229a9a',
  es: '656f83bfefbae46ea6c6ea11ffc44e90829ba193fdd0fc65a4449faa4a8d81c4',
}

/** Warnung, falls auch ihre Übersetzung fehlt. */
const WARNING = 'The author notice of this copy is missing or has been changed, so this copy may not be the original. The original text is shown here. The launcher keeps working normally and nothing is sent anywhere.'
const WARNING_SHORT = 'Warning: author notice missing or changed.'

/** Beta-Sprachen zeigen den englischen Text (Rückfall der Übersetzung). */
function noticeLanguage(locale: Locale): NoticeLanguage {
  return locale === 'de' || locale === 'es' ? locale : 'en'
}

const K = Uint32Array.from(
  Array.from({ length: 64 }, (_, i) => {
    // Die ersten 32 Bit der Nachkommastellen der Kubikwurzeln der ersten 64 Primzahlen.
    let n = 2
    for (let found = 0; ; n++) {
      let prime = true
      for (let d = 2; d * d <= n; d++) if (n % d === 0) prime = false
      if (prime && found++ === i) break
    }
    return Math.floor((Math.cbrt(n) % 1) * 2 ** 32)
  }),
)

/** SHA-256 als Hex, synchron und ohne `crypto.subtle` (das nicht in jedem App-Webview bereitsteht). */
export function sha256Hex(text: string): string {
  const bytes = new TextEncoder().encode(text)
  const padded = new Uint8Array((((bytes.length + 8) >> 6) + 1) * 64)
  padded.set(bytes)
  padded[bytes.length] = 0x80
  const view = new DataView(padded.buffer)
  view.setUint32(padded.length - 8, Math.floor((bytes.length * 8) / 2 ** 32))
  view.setUint32(padded.length - 4, (bytes.length * 8) >>> 0)
  const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
  const w = new Uint32Array(64)
  const rotr = (x: number, n: number) => (x >>> n) | (x << (32 - n))
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4)
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15]!
      const b = w[i - 2]!
      w[i] = (w[i - 16]! + (rotr(a, 7) ^ rotr(a, 18) ^ (a >>> 3)) + w[i - 7]! + (rotr(b, 17) ^ rotr(b, 19) ^ (b >>> 10))) >>> 0
    }
    let [a, b, c, d, e, f, g, hh] = h as [number, number, number, number, number, number, number, number]
    for (let i = 0; i < 64; i++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i]! + w[i]!) >>> 0
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0
      hh = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }
    ;[a, b, c, d, e, f, g, hh].forEach((v, i) => (h[i] = (h[i]! + v) >>> 0))
  }
  return h.map((v) => v.toString(16).padStart(8, '0')).join('')
}

/** Stimmt `text` mit dem hinterlegten Hash dieser Sprache überein? */
export function authorNoticeMatches(locale: Locale, text: string | null | undefined): boolean {
  return typeof text === 'string' && sha256Hex(text) === SHA256[noticeLanguage(locale)]
}

export function authorNoticeOriginal(locale: Locale): string {
  return ORIGINAL[noticeLanguage(locale)]
}

/** Stand für die aktive Sprache: unverändert? Anzuzeigen ist bei Abweichung immer das Original aus dem Code. */
export const authorNotice = computed(() => {
  const locale = currentLocale.value
  const message = t('settings.about.notice')
  const intact = authorNoticeMatches(locale, message) && authorNoticeMatches(locale, authorNoticeOriginal(locale))
  return {
    intact,
    text: intact ? message : authorNoticeOriginal(locale),
    warning: hasKey('settings.about.warning') ? t('settings.about.warning') : WARNING,
    warningShort: hasKey('settings.about.warningShort') ? t('settings.about.warningShort') : WARNING_SHORT,
  }
})
