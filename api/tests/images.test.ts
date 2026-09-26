import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { encode as jpegEncode } from 'jpeg-js'
import { PNG } from 'pngjs'
import { describe, expect, it } from 'vitest'
import { getAttachment, readAttachment, sweepPendingAttachments, uploadAttachment } from '../server/lib/attachments'
import { deleteMessage, listMessages, openDm, sendMessage } from '../server/lib/chat'
import { deleteUser } from '../server/lib/users'
import { downscale, imageSize, jpegOrientation, processChatImage, sanitizeServerIcon, sniffImage } from '../server/lib/images'
import { befriend, codeAsync, players } from './chathelpers'
import { chunk, chunks, makeEnv, solidPng, withChunks } from './helpers'

function rgba(w: number, h: number, fn: (x: number, y: number) => [number, number, number, number]): Uint8Array {
  const d = new Uint8Array(w * h * 4)
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) d.set(fn(x, y), (y * w + x) * 4)
  return d
}

function jpeg(w: number, h: number, exifOrientation?: number): Buffer {
  const j = jpegEncode({ width: w, height: h, data: rgba(w, h, (x) => [x % 256, 80, 160, 255]) }, 90).data
  if (!exifOrientation) return j
  // APP1 „Exif“ mit IFD0 → 0x0112 (Orientation) direkt nach SOI einfügen.
  const tiff = Buffer.alloc(26)
  tiff.write('MM', 0, 'latin1')
  tiff.writeUInt16BE(42, 2)
  tiff.writeUInt32BE(8, 4)
  tiff.writeUInt16BE(1, 8)
  tiff.writeUInt16BE(0x0112, 10)
  tiff.writeUInt16BE(3, 12)
  tiff.writeUInt32BE(1, 14)
  tiff.writeUInt16BE(exifOrientation, 18)
  const payload = Buffer.concat([Buffer.from('Exif\0\0', 'latin1'), tiff])
  const seg = Buffer.alloc(4)
  seg.writeUInt16BE(0xffe1, 0)
  seg.writeUInt16BE(payload.length + 2, 2)
  return Buffer.concat([j.subarray(0, 2), seg, payload, j.subarray(2)])
}

async function webp(w: number, h: number, alpha = false): Promise<Buffer> {
  const require = createRequire(import.meta.url)
  const enc = await import('@jsquash/webp/encode.js')
  // Node unterstützt Wasm-SIMD → der Kodierer wählt die SIMD-Variante.
  const file = 'webp_enc_simd.wasm'
  await enc.init(await WebAssembly.compile(readFileSync(require.resolve(`@jsquash/webp/codec/enc/${file}`))))
  const data = rgba(w, h, (x, y) => [x % 256, y % 256, 90, alpha ? 128 : 255])
  const g = globalThis as { ImageData?: unknown }
  g.ImageData ??= class {
    constructor(readonly data: Uint8ClampedArray, readonly width: number, readonly height: number) {}
  }
  const out = await enc.default({ data: new Uint8ClampedArray(data.buffer), width: w, height: h, colorSpace: 'srgb' } as ImageData, { lossless: alpha ? 1 : 0 })
  return Buffer.from(out)
}

describe('image processing', () => {
  it('detects formats by magic bytes and reads sizes from headers', async () => {
    const png = solidPng(30, 20)
    expect(sniffImage(png)).toBe('png')
    expect(imageSize(png, 'png')).toEqual({ width: 30, height: 20 })
    const j = jpeg(40, 10)
    expect(sniffImage(j)).toBe('jpeg')
    expect(imageSize(j, 'jpeg')).toEqual({ width: 40, height: 10 })
    const w = await webp(33, 17)
    expect(sniffImage(w)).toBe('webp')
    expect(imageSize(w, 'webp')).toEqual({ width: 33, height: 17 })
    expect(sniffImage(Buffer.from('<svg></svg>'))).toBeNull()
  })

  it('re-encodes: opaque → JPEG, transparent → PNG, metadata gone', async () => {
    const withText = withChunks(solidPng(50, 40, [10, 20, 30, 255]), [chunk('tEXt', Buffer.from('Comment\0secret-gps', 'latin1'))])
    const opaque = await processChatImage(withText, 'image/png')
    expect(opaque.full.mime).toBe('image/jpeg')
    expect(opaque.full.data.includes(Buffer.from('secret-gps'))).toBe(false)
    const alpha = await processChatImage(solidPng(50, 40, [10, 20, 30, 100]), 'image/png')
    expect(alpha.full.mime).toBe('image/png')
    expect(chunks(alpha.full.data).map((c) => c.type)).toEqual(['IHDR', 'IDAT', 'IEND'])
    const p = PNG.sync.read(alpha.full.data)
    expect([p.data[0], p.data[3]]).toEqual([10, 100])
  })

  it('decodes JPEG (with EXIF rotation) and WebP', async () => {
    expect(jpegOrientation(jpeg(8, 4, 6))).toBe(6)
    const rotated = await processChatImage(jpeg(80, 40, 6), 'image/jpeg')
    expect([rotated.full.width, rotated.full.height]).toEqual([40, 80])
    expect(rotated.full.data.includes(Buffer.from('Exif'))).toBe(false)
    const w = await processChatImage(await webp(64, 48), 'image/webp')
    expect([w.full.mime, w.full.width, w.full.height]).toEqual(['image/jpeg', 64, 48])
    const wa = await processChatImage(await webp(20, 20, true), 'image/webp')
    expect(wa.full.mime).toBe('image/png')
  })

  it('downscales to 2048 px and makes a ≤400 px thumbnail', async () => {
    const big = new PNG({ width: 3000, height: 1000 })
    big.data.fill(255)
    const r = await processChatImage(PNG.sync.write(big), 'image/png')
    expect([r.full.width, r.full.height]).toEqual([2048, 683])
    expect([r.thumb.width, r.thumb.height]).toEqual([400, 133])
    // Box-Filter: Mittelwert aus Schwarz/Weiß-Streifen.
    const striped = downscale({ width: 4, height: 2, data: rgba(4, 2, (x) => (x % 2 ? [255, 255, 255, 255] : [0, 0, 0, 255])) }, 2)
    expect([striped.width, striped.height, striped.data[0]]).toEqual([2, 1, 128])
  })

  it('rejects mismatched types, garbage, animation and oversized dimensions', async () => {
    expect(await codeAsync(() => processChatImage(solidPng(4, 4), 'image/jpeg'))).toBe('unsupported_media_type')
    expect(await codeAsync(() => processChatImage(Buffer.from('GIF89a....'), 'image/png'))).toBe('unsupported_media_type')
    const huge = Buffer.from(solidPng(4, 4))
    huge.writeUInt32BE(9000, 16) // IHDR-Breite (Prüfsumme egal – wird vor dem Dekodieren abgelehnt)
    expect(await codeAsync(() => processChatImage(huge, 'image/png'))).toBe('image_too_large')
    const broken = Buffer.concat([jpeg(8, 8).subarray(0, 40)])
    expect(await codeAsync(() => processChatImage(broken, 'image/jpeg'))).toBe('invalid_image')
    const anim = Buffer.alloc(30)
    anim.write('RIFF', 0, 'latin1')
    anim.write('WEBPVP8X', 8, 'latin1')
    anim[20] = 0x02
    expect(await codeAsync(() => processChatImage(anim, 'image/webp'))).toBe('animated_image')
    expect(await codeAsync(() => processChatImage(Buffer.alloc(5 * 1024 * 1024 + 1), 'image/png'))).toBe('payload_too_large')
  })

  it('server icons: only 64×64 PNG data URLs, re-encoded', () => {
    const icon = `data:image/png;base64,${solidPng(64, 64).toString('base64')}`
    expect(sanitizeServerIcon(icon)).toMatch(/^data:image\/png;base64,/)
    expect(sanitizeServerIcon(`data:image/png;base64,${solidPng(32, 32).toString('base64')}`)).toBeNull()
    expect(sanitizeServerIcon('data:text/html;base64,PHNjcmlwdD4=')).toBeNull()
    expect(sanitizeServerIcon(42)).toBeNull()
  })
})

describe('chat attachments', () => {
  it('upload → send with up to 10 images → members can read, strangers cannot; files encrypted and cleaned up', async () => {
    const env = makeEnv()
    const [a, b, c] = await players(env, 'Alex', 'Bob', 'Carl')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const att = await uploadAttachment(env.ctx, a!.uuid, solidPng(120, 60, [200, 10, 10, 255]), 'image/png')
    expect(att).toMatchObject({ mime: 'image/jpeg', width: 120, height: 60, path: `/v1/chat/attachments/${att.id}` })
    expect(att.thumb.path).toBe(`/v1/chat/attachments/${att.id}?thumb=1`)
    // Verschlüsselt auf der Platte (kein JPEG-Header lesbar).
    const dir = join(env.ctx.chatDir, att.id.slice(1, 3))
    const files = readdirSync(dir)
    expect(files.sort()).toEqual([`${att.id}.bin`, `${att.id}.t.bin`])
    expect(readFileSync(join(dir, `${att.id}.bin`)).subarray(0, 64).includes(Buffer.from([0xff, 0xd8, 0xff]))).toBe(false)

    // Fremde Uploads lassen sich nicht anhängen.
    const foreign = await uploadAttachment(env.ctx, c!.uuid, solidPng(10, 10), 'image/png')
    expect(await codeAsync(async () => sendMessage(env.ctx, a!.uuid, dm.id, { attachments: [foreign.id] }))).toBe('attachment_not_found')
    const m = sendMessage(env.ctx, a!.uuid, dm.id, { attachments: [att.id], text: 'Screenshot' }).message
    expect(m.attachments.map((x) => x.id)).toEqual([att.id])
    expect(await codeAsync(async () => sendMessage(env.ctx, a!.uuid, dm.id, { attachments: [att.id] }))).toBe('attachment_not_found')
    const row = getAttachment(env.ctx, att.id)!
    expect(readAttachment(env.ctx, row, false).subarray(0, 3)).toEqual(Buffer.from([0xff, 0xd8, 0xff]))
    expect(listMessages(env.ctx, b!.uuid, dm.id, { limit: 5 }).messages[0]!.attachments).toHaveLength(1)

    // Zu viele Bilder.
    const ids = []
    for (let i = 0; i < 11; i++) ids.push((await uploadAttachment(env.ctx, a!.uuid, solidPng(8, 8), 'image/png')).id)
    expect(await codeAsync(async () => sendMessage(env.ctx, a!.uuid, dm.id, { attachments: ids }))).toBe('too_many_attachments')
    expect(sendMessage(env.ctx, a!.uuid, dm.id, { attachments: ids.slice(0, 10) }).message.attachments).toHaveLength(10)

    // Löschen der Nachricht entfernt die Dateien.
    deleteMessage(env.ctx, a!.uuid, m.id)
    expect(existsSync(join(dir, `${att.id}.bin`))).toBe(false)
    expect(getAttachment(env.ctx, att.id)).toBeUndefined()

    // Nicht verwendete Uploads verfallen.
    env.clock.advance(61 * 60_000)
    expect(sweepPendingAttachments(env.ctx)).toBe(2) // foreign + 11.
    // Kontolöschung räumt alle Bilder weg.
    deleteUser(env.ctx, a!.uuid)
    const left = readdirSync(env.ctx.chatDir, { recursive: true }).filter((f) => String(f).endsWith('.bin'))
    expect(left).toEqual([])
  })

  it('quota and global storage limit', async () => {
    const env = makeEnv({ limits: { maxAttachmentBytesPerUser: 1 } })
    const [a] = await players(env, 'Alex')
    await uploadAttachment(env.ctx, a!.uuid, solidPng(8, 8), 'image/png')
    expect(await codeAsync(() => uploadAttachment(env.ctx, a!.uuid, solidPng(8, 8), 'image/png'))).toBe('storage_quota')
    const env2 = makeEnv({ env: { CHAT_STORAGE_MAX_MB: '10' } })
    const [x] = await players(env2, 'Xena')
    env2.ctx.config.chatStorageMaxBytes = 1
    await uploadAttachment(env2.ctx, x!.uuid, solidPng(8, 8), 'image/png')
    expect(await codeAsync(() => uploadAttachment(env2.ctx, x!.uuid, solidPng(8, 8), 'image/png'))).toBe('storage_full')
  })
})
