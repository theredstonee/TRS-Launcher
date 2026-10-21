// Background: a low-resolution "deepslate + redstone wire" field with signal pulses running along the wires
// (same idea as the launcher's live circuit). One canvas pixel = one cell, scaled up pixelated by CSS.

function rng(seed: number) {
  return () => {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

interface Wire {
  cells: number[]
  speed: number
  offset: number
}

const CELL = 12
const TAIL = 9

export function startBackground(cv: HTMLCanvasElement, reduced: boolean) {
  const g = cv.getContext('2d', { alpha: false })!
  let w = 0
  let h = 0
  let base: ImageData | null = null
  let wires: Wire[] = []
  let raf = 0
  let last = 0

  function build() {
    w = Math.max(16, Math.ceil(innerWidth / CELL))
    h = Math.max(9, Math.ceil(innerHeight / CELL))
    cv.width = w
    cv.height = h
    const r = rng(20261021)
    const img = g.createImageData(w, h)
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const v = r()
        const i = (y * w + x) * 4
        // deepslate tile: dark blue-grey with a little noise, brick seams every 4 cells
        const seam = y % 4 === 0 || (x + (Math.floor(y / 4) % 2) * 2) % 4 === 0
        const l = seam ? 13 : 19 + Math.floor(v * 7)
        img.data[i] = l
        img.data[i + 1] = l
        img.data[i + 2] = l + 6
        img.data[i + 3] = 255
      }
    }
    wires = []
    const count = Math.round((w * h) / 320)
    for (let n = 0; n < count; n++) {
      let x = Math.floor(r() * w)
      let y = Math.floor(r() * h)
      let dir = Math.floor(r() * 4)
      const cells: number[] = []
      const len = 24 + Math.floor(r() * 60)
      let seg = 3 + Math.floor(r() * 10)
      for (let k = 0; k < len; k++) {
        if (x < 0 || y < 0 || x >= w || y >= h) break
        cells.push(y * w + x)
        if (--seg <= 0) {
          dir = (dir + (r() < 0.5 ? 1 : 3)) % 4
          seg = 3 + Math.floor(r() * 10)
        }
        if (dir === 0) x++
        else if (dir === 1) y++
        else if (dir === 2) x--
        else y--
      }
      if (cells.length < 8) continue
      for (const c of cells) {
        const i = c * 4
        img.data[i] = 52
        img.data[i + 1] = 12
        img.data[i + 2] = 12
      }
      wires.push({ cells, speed: 9 + r() * 14, offset: r() * 200 })
    }
    base = img
  }

  function draw(now: number) {
    if (!base) return
    g.putImageData(base, 0, 0)
    const t = now / 1000
    for (const wre of wires) {
      const L = wre.cells.length
      const p = (t * wre.speed + wre.offset) % (L + TAIL * 4)
      for (let k = 0; k < TAIL; k++) {
        const idx = Math.floor(p) - k
        if (idx < 0 || idx >= L) continue
        const b = 1 - k / TAIL
        const c = wre.cells[idx]!
        const r = Math.round(52 + (255 - 52) * b)
        const gg = Math.round(14 + (70 - 14) * b * b)
        const bb = Math.round(11 + (50 - 11) * b * b)
        g.fillStyle = `rgb(${r},${gg},${bb})`
        g.fillRect(c % w, Math.floor(c / w), 1, 1)
      }
    }
  }

  function loop(now: number) {
    raf = requestAnimationFrame(loop)
    if (document.hidden || document.body.classList.contains('on-intro')) return
    if (now - last < 50) return // ~20 fps is plenty for this
    last = now
    draw(now)
  }

  build()
  draw(performance.now())
  addEventListener('resize', () => {
    build()
    draw(performance.now())
  })
  if (!reduced) raf = requestAnimationFrame(loop)
  return () => cancelAnimationFrame(raf)
}
