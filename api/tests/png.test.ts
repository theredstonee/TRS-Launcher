import { deflateSync } from 'node:zlib'
import { PNG } from 'pngjs'
import { describe, expect, it } from 'vitest'
import { inspectPng, sanitizeCapeUpload } from '../server/lib/png'
import { chunk, chunks, solidPng, withChunks } from './helpers'

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code: string }).code
  }
  return 'ok'
}

const text = (k: string, v: string) => chunk('tEXt', Buffer.from(`${k}\0${v}`, 'latin1'))

describe('cape upload sanitizing', () => {
  it('accepts a valid 64x32 PNG and re-encodes it without metadata', () => {
    const src = withChunks(solidPng(64, 32), [text('Comment', '<script>alert(1)</script>'), chunk('tIME', Buffer.alloc(7))])
    const out = sanitizeCapeUpload(src)
    expect(out).toMatchObject({ width: 64, height: 32, source: 'full' })
    expect(chunks(out.png).map((c) => c.type)).toEqual(['IHDR', 'IDAT', 'IEND'])
    expect(out.png.includes(Buffer.from('<script>'))).toBe(false)
    const img = PNG.sync.read(out.png)
    expect([...img.data.subarray(0, 4)]).toEqual([200, 30, 20, 255])
  })

  it('accepts HD sizes up to 256x128 (scale 4)', () => {
    expect(sanitizeCapeUpload(solidPng(128, 64)).width).toBe(128)
    expect(sanitizeCapeUpload(solidPng(192, 96)).width).toBe(192)
    expect(sanitizeCapeUpload(solidPng(256, 128)).width).toBe(256)
    expect(code(() => sanitizeCapeUpload(solidPng(512, 256)))).toBe('invalid_dimensions')
  })

  it('converts the 22x17 cape-only format into the 64x32 layout', () => {
    const out = sanitizeCapeUpload(solidPng(22, 17, [10, 20, 30, 255]))
    expect(out).toMatchObject({ width: 64, height: 32, source: 'cape-only' })
    const img = PNG.sync.read(out.png)
    const px = (x: number, y: number) => [...img.data.subarray((y * 64 + x) * 4, (y * 64 + x) * 4 + 4)]
    expect(px(0, 0)).toEqual([10, 20, 30, 255])
    expect(px(21, 16)).toEqual([10, 20, 30, 255])
    expect(px(22, 0)).toEqual([0, 0, 0, 0])
    expect(px(0, 17)).toEqual([0, 0, 0, 0])
    expect(sanitizeCapeUpload(solidPng(44, 34)).width).toBe(128)
  })

  it('rejects wrong dimensions', () => {
    expect(code(() => sanitizeCapeUpload(solidPng(65, 32)))).toBe('invalid_dimensions')
    expect(code(() => sanitizeCapeUpload(solidPng(64, 64)))).toBe('invalid_dimensions')
    expect(code(() => sanitizeCapeUpload(solidPng(23, 17)))).toBe('invalid_dimensions')
  })

  it('rejects oversized files before decoding', () => {
    const big = withChunks(solidPng(64, 32), [text('pad', 'x'.repeat(300 * 1024))])
    expect(code(() => sanitizeCapeUpload(big))).toBe('payload_too_large')
  })

  it('rejects files that are not PNGs', () => {
    expect(code(() => sanitizeCapeUpload(Buffer.from('<?php system($_GET["c"]); ?>'.padEnd(100, ' '))))).toBe('invalid_png')
    expect(code(() => sanitizeCapeUpload(Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(100)])))).toBe('invalid_png')
    // Echte Signatur, aber Müll dahinter.
    expect(code(() => sanitizeCapeUpload(Buffer.concat([solidPng(64, 32).subarray(0, 8), Buffer.alloc(100, 7)])))).toBe('invalid_png')
  })

  it('rejects polyglots with data after IEND', () => {
    const zip = Buffer.from('PK\x03\x04 hidden archive <html><script>x</script></html>', 'latin1')
    expect(code(() => sanitizeCapeUpload(Buffer.concat([solidPng(64, 32), zip])))).toBe('invalid_png')
  })

  it('rejects animated PNGs and unknown or private chunks', () => {
    const actl = chunk('acTL', Buffer.from([0, 0, 0, 2, 0, 0, 0, 0]))
    expect(code(() => sanitizeCapeUpload(withChunks(solidPng(64, 32), [actl], true)))).toBe('animated_png')
    expect(code(() => sanitizeCapeUpload(withChunks(solidPng(64, 32), [chunk('prVt', Buffer.from('x'))])))).toBe('invalid_png')
    expect(code(() => sanitizeCapeUpload(withChunks(solidPng(64, 32), [chunk('EXEC', Buffer.from('x'))])))).toBe('invalid_png')
  })

  it('rejects corrupted checksums and split image data', () => {
    const png = Buffer.from(solidPng(64, 32))
    png[png.length - 20] = png[png.length - 20]! ^ 0xff
    expect(code(() => sanitizeCapeUpload(png))).toBe('invalid_png')
    const parts = chunks(solidPng(64, 32))
    const idat = parts.find((c) => c.type === 'IDAT')!.data
    const split = Buffer.concat([
      solidPng(64, 32).subarray(0, 8),
      chunk('IHDR', parts[0]!.data),
      chunk('IDAT', idat.subarray(0, 10)),
      text('a', 'b'),
      chunk('IDAT', idat.subarray(10)),
      chunk('IEND', Buffer.alloc(0)),
    ])
    expect(code(() => sanitizeCapeUpload(split))).toBe('invalid_png')
  })

  it('refuses decompression bombs', () => {
    const ihdr = chunks(solidPng(64, 32))[0]!
    const bomb = Buffer.concat([
      solidPng(64, 32).subarray(0, 8),
      chunk('IHDR', ihdr.data),
      chunk('IDAT', deflateSync(Buffer.alloc(40 * 1024 * 1024))),
      chunk('IEND', Buffer.alloc(0)),
    ])
    expect(bomb.length).toBeLessThan(256 * 1024)
    expect(code(() => sanitizeCapeUpload(bomb))).toBe('invalid_png')
  })

  it('rejects fully transparent capes and clears colour under transparent pixels', () => {
    expect(code(() => sanitizeCapeUpload(solidPng(64, 32, [255, 0, 0, 0])))).toBe('empty_cape')
    const p = new PNG({ width: 64, height: 32 })
    for (let i = 0; i < 64 * 32; i++) p.data.set([99, 98, 97, 0], i * 4) // versteckte Farbe
    p.data.set([1, 2, 3, 255], 0)
    const img = PNG.sync.read(sanitizeCapeUpload(PNG.sync.write(p)).png)
    expect([...img.data.subarray(4, 8)]).toEqual([0, 0, 0, 0])
    expect([...img.data.subarray(0, 4)]).toEqual([1, 2, 3, 255])
  })

  it('inspectPng reports the header of built-in strips', () => {
    expect(inspectPng(solidPng(128, 512)).header).toMatchObject({ width: 128, height: 512, colorType: 6 })
  })
})
