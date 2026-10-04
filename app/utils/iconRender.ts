import {
  decodeGrid,
  gridFromRgba,
  hexToPx,
  ICON_OUTPUT_SIZE,
  pxToRgba,
  type GridSize,
  type IconBackground,
  type IconSource,
  type PixelGrid,
} from './iconEditor'

/*
 * Symbol-Editor: Zeichnen im Browser (Canvas). Hintergrund + Muster + Motiv → fertiges PNG.
 * Muster sind eigene Pixel-Zeichnungen im Stil der Startseite (Redstone-Leitung, Deepslate, Punkte).
 */

function rgba(hex: string, alpha: number): string {
  const [r, g, b] = pxToRgba(hexToPx(hex))
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/** Raster als Canvas in Originalgröße (1 Pixel = 1 Zelle). */
export function gridCanvas(grid: PixelGrid): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = grid.size
  canvas.height = grid.size
  const ctx = canvas.getContext('2d')!
  const image = ctx.createImageData(grid.size, grid.size)
  grid.px.forEach((p, i) => image.data.set(pxToRgba(p), i * 4))
  ctx.putImageData(image, 0, 0)
  return canvas
}

const images = new Map<string, Promise<HTMLImageElement>>()

/** Data-URL laden (zwischengespeichert). */
export function loadImage(src: string): Promise<HTMLImageElement> {
  let pending = images.get(src)
  if (!pending) {
    pending = new Promise((resolve, reject) => {
      const img = new Image()
      img.onload = () => resolve(img)
      img.onerror = () => reject(new Error('image'))
      img.src = src
    })
    images.set(src, pending)
    if (images.size > 64) images.delete(images.keys().next().value!)
  }
  return pending
}

/** Bild (z. B. 16×16-Textur) → Raster; größere Bilder werden pixelgenau verkleinert. */
export async function gridFromImage(src: string, size: GridSize): Promise<PixelGrid> {
  const img = await loadImage(src)
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.imageSmoothingEnabled = false
  ctx.drawImage(img, 0, 0, size, size)
  return gridFromRgba(ctx.getImageData(0, 0, size, size).data, size)
}

function drawPattern(ctx: CanvasRenderingContext2D, bg: IconBackground, size: number) {
  const unit = size / 32
  const cell = (x: number, y: number, w = 1, h = 1) => ctx.fillRect(Math.round(x * unit), Math.round(y * unit), Math.ceil(w * unit), Math.ceil(h * unit))
  if (bg.pattern === 'deepslate') {
    // Fliesen 8 × 8 mit dunklen Fugen und hellen Flecken.
    ctx.fillStyle = 'rgba(0, 0, 0, 0.28)'
    for (let i = 7; i < 32; i += 8) {
      cell(0, i, 32, 1)
      cell(i, 0, 1, 32)
    }
    ctx.fillStyle = 'rgba(255, 255, 255, 0.05)'
    for (const [x, y] of [[2, 1], [5, 3], [11, 2], [13, 5], [18, 1], [21, 4], [27, 2], [3, 10], [9, 12], [20, 9], [26, 13], [4, 19], [14, 18], [22, 21], [29, 17], [1, 27], [10, 25], [17, 28], [25, 26]]) cell(x!, y!)
    ctx.fillStyle = 'rgba(0, 0, 0, 0.18)'
    for (const [x, y] of [[4, 4], [12, 1], [19, 5], [28, 6], [6, 13], [15, 11], [25, 10], [2, 21], [12, 22], [27, 20], [7, 29], [20, 26], [29, 29]]) cell(x!, y!)
  } else if (bg.pattern === 'dots') {
    ctx.fillStyle = rgba(bg.patternColor, 0.22)
    for (let y = 2; y < 32; y += 5) for (let x = (y % 2) * 2 + 1; x < 32; x += 5) cell(x, y)
  } else if (bg.pattern === 'wire') {
    // Redstone-Leitung: Bahnen auf dem Raster, an Ecken ein heller Punkt.
    const path: [number, number][] = [[0, 6], [9, 6], [9, 14], [24, 14], [24, 4], [32, 4]]
    const path2: [number, number][] = [[0, 24], [6, 24], [6, 20], [16, 20], [16, 28], [32, 28]]
    for (const line of [path, path2]) {
      for (let i = 0; i + 1 < line.length; i++) {
        const [x0, y0] = line[i]!
        const [x1, y1] = line[i + 1]!
        ctx.fillStyle = rgba(bg.patternColor, 0.28)
        cell(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0) + 2, Math.abs(y1 - y0) + 2)
        ctx.fillStyle = rgba(bg.patternColor, 0.5)
        cell(x0, y0, 2, 2)
      }
    }
  }
}

function roundedRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

export function drawBackground(ctx: CanvasRenderingContext2D, bg: IconBackground, size: number) {
  ctx.clearRect(0, 0, size, size)
  if (bg.fill === 'solid') {
    ctx.fillStyle = bg.color
    ctx.fillRect(0, 0, size, size)
  } else if (bg.fill === 'gradient') {
    const rad = ((bg.angle - 90) * Math.PI) / 180
    const dx = (Math.cos(rad) * size) / 2
    const dy = (Math.sin(rad) * size) / 2
    const gradient = ctx.createLinearGradient(size / 2 - dx, size / 2 - dy, size / 2 + dx, size / 2 + dy)
    gradient.addColorStop(0, bg.color)
    gradient.addColorStop(1, bg.color2)
    ctx.fillStyle = gradient
    ctx.fillRect(0, 0, size, size)
  }
  if (bg.fill !== 'none') drawPattern(ctx, bg, size)
  if (bg.glow) {
    const glow = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size * 0.5)
    glow.addColorStop(0, rgba(bg.glowColor, 0.55))
    glow.addColorStop(1, rgba(bg.glowColor, 0))
    ctx.fillStyle = glow
    ctx.fillRect(0, 0, size, size)
  }
}

function drawBorder(ctx: CanvasRenderingContext2D, bg: IconBackground, size: number) {
  if (!bg.border) return
  const width = Math.max(1, Math.round(size * 0.055))
  ctx.strokeStyle = bg.borderColor
  ctx.lineWidth = width
  roundedRect(ctx, width / 2, width / 2, size - width, size - width, size * 0.14)
  ctx.stroke()
}

/** Zeichnet das ganze Symbol in `size` × `size`. */
export async function drawIcon(ctx: CanvasRenderingContext2D, source: IconSource, size: number) {
  drawBackground(ctx, source.bg, size)
  const layer = source.layer
  let art: CanvasImageSource | null = null
  let artSize = 0
  let crisp = true
  if (layer.kind === 'pixels') {
    const grid = decodeGrid(layer)
    if (grid) {
      art = gridCanvas(grid)
      artSize = grid.size
    }
  } else if (layer.kind === 'image') {
    try {
      const img = await loadImage(layer.png)
      art = img
      artSize = img.naturalWidth
      crisp = false
    } catch {
      art = null
    }
  }
  if (art && artSize) {
    // Pixel-Motive auf ganze Vielfache skalieren – scharfe Kanten.
    let target = Math.round(size * source.scale)
    if (crisp && target >= artSize) target = Math.floor(target / artSize) * artSize
    const offset = Math.round((size - target) / 2)
    ctx.save()
    ctx.imageSmoothingEnabled = !crisp
    ctx.imageSmoothingQuality = 'high'
    if (source.bg.glow) {
      ctx.shadowColor = rgba(source.bg.glowColor, 0.8)
      ctx.shadowBlur = size * 0.08
    }
    ctx.drawImage(art, offset, offset, target, target)
    ctx.restore()
  }
  drawBorder(ctx, source.bg, size)
}

/** Fertiges Symbol als PNG (Base64 ohne Präfix) für `save_instance_icon`. */
export async function renderIconPng(source: IconSource, size = ICON_OUTPUT_SIZE): Promise<string> {
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  await drawIcon(canvas.getContext('2d')!, source, size)
  return canvas.toDataURL('image/png').slice('data:image/png;base64,'.length)
}

/** Raster vergrößert als PNG (Export aus dem Pixel-Editor). */
export function gridPng(grid: PixelGrid, scale: number): string {
  const canvas = document.createElement('canvas')
  canvas.width = grid.size * scale
  canvas.height = grid.size * scale
  const ctx = canvas.getContext('2d')!
  ctx.imageSmoothingEnabled = false
  ctx.drawImage(gridCanvas(grid), 0, 0, canvas.width, canvas.height)
  return canvas.toDataURL('image/png').slice('data:image/png;base64,'.length)
}

/** Raster als kleine Data-URL (Vorschau in Rastern/Listen). */
export function gridDataUrl(grid: PixelGrid): string {
  return gridCanvas(grid).toDataURL('image/png')
}
