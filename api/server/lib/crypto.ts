import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto'

/**
 * Verschlüsselung „at rest“ für Chat-Inhalte (Texte, Gruppennamen, Bilder, Meldungs-Beweise).
 *
 * AES-256-GCM, je Datensatz ein zufälliger 96-Bit-IV. Die AAD bindet den Geheimtext an seinen
 * Datensatz (z. B. `msg:<id>`), damit sich Inhalte in der Datenbank nicht zwischen Zeilen
 * vertauschen lassen. Format (Bytes):
 *
 *   0x01 | Länge der Schlüssel-ID (1 Byte) | Schlüssel-ID (ASCII) | IV (12) | Tag (16) | Geheimtext
 *
 * Schlüsseltausch: neuen Schlüssel vorne in `CHAT_KEYS` eintragen, alte dahinter lassen. Neue Daten
 * nutzen den ersten Schlüssel; der Hintergrundjob (`rotateChatKeys`) verschlüsselt alte Daten nach
 * und nach neu. Erst wenn er fertig ist (Log „chat key rotation done“), darf der alte Schlüssel weg.
 */
export class ChatCipher {
  private readonly byId: Map<string, Buffer>
  readonly activeId: string

  constructor(keys: { id: string, key: Buffer }[]) {
    if (keys.length === 0) throw new Error('ChatCipher needs at least one key')
    this.byId = new Map(keys.map((k) => [k.id, k.key]))
    this.activeId = keys[0]!.id
  }

  encrypt(plain: Buffer | string, aad: string): Buffer {
    const key = this.byId.get(this.activeId)!
    const iv = randomBytes(12)
    const c = createCipheriv('aes-256-gcm', key, iv)
    c.setAAD(Buffer.from(aad, 'utf8'))
    const ct = Buffer.concat([c.update(typeof plain === 'string' ? Buffer.from(plain, 'utf8') : plain), c.final()])
    const kid = Buffer.from(this.activeId, 'ascii')
    return Buffer.concat([Buffer.from([1, kid.length]), kid, iv, c.getAuthTag(), ct])
  }

  /** Wirft bei unbekanntem Schlüssel oder manipulierten Daten. */
  decrypt(blob: Uint8Array, aad: string): Buffer {
    const buf = Buffer.from(blob.buffer, blob.byteOffset, blob.byteLength)
    if (buf.length < 2 || buf[0] !== 1) throw new Error('unknown ciphertext format')
    const kidLen = buf[1]!
    const kid = buf.toString('ascii', 2, 2 + kidLen)
    const key = this.byId.get(kid)
    if (!key) throw new Error(`unknown chat key id ${kid}`)
    const off = 2 + kidLen
    const iv = buf.subarray(off, off + 12)
    const tag = buf.subarray(off + 12, off + 28)
    const d = createDecipheriv('aes-256-gcm', key, iv)
    d.setAAD(Buffer.from(aad, 'utf8'))
    d.setAuthTag(tag)
    return Buffer.concat([d.update(buf.subarray(off + 28)), d.final()])
  }

  decryptText(blob: Uint8Array, aad: string): string {
    return this.decrypt(blob, aad).toString('utf8')
  }

  /** Schlüssel-ID eines Geheimtexts (für den Schlüsseltausch). */
  static keyIdOf(blob: Uint8Array): string {
    const buf = Buffer.from(blob.buffer, blob.byteOffset, blob.byteLength)
    return buf.toString('ascii', 2, 2 + (buf[1] ?? 0))
  }
}
