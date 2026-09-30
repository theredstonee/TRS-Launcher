import type { SkinVariant } from '~/types'

// Vorschaubilder der Skin-Karten: EINE unsichtbare skinview3d-Szene rendert
// nacheinander jede Figur schräg von vorne (bzw. für Umhänge schräg von hinten)
// und macht daraus eine Data-URL. So gibt es keinen WebGL-Kontext je Karte,
// sondern höchstens einen zusätzlichen – und der wird nach kurzer Ruhe wieder
// freigegeben. Gleiche Anfrage = gleiches Bild (Cache nach Textur-Hash).

export interface ThumbRequest {
  /** Skin-Textur als Data-URL. */
  skin: string
  variant: SkinVariant
  /** Umhang-Textur als Data-URL (bei Streifen wird das erste Bild genommen). */
  cape?: string | null
  /** Bilder im senkrechten Umhang-Streifen (TRS, animiert). */
  capeFrames?: number
  /** `front` = schräg von vorne, `back` = schräg von hinten (Umhänge). */
  view?: 'front' | 'back'
}

/** Größe in CSS-Pixeln; gerendert wird doppelt so groß (scharf auf HiDPI). */
export const THUMB_WIDTH = 150
export const THUMB_HEIGHT = 200
const PIXEL_RATIO = 2
/** So lange bleibt die Szene nach dem letzten Bild bestehen. */
const IDLE_MS = 8000
const MAX_CACHE = 400

/** FNV-1a über die ganze Zeichenkette (Data-URLs sind klein). */
export function textHash(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return `${(h >>> 0).toString(36)}.${text.length.toString(36)}`
}

export function thumbKey(r: ThumbRequest): string {
  const cape = r.cape ? `${textHash(r.cape)}x${r.capeFrames ?? 1}` : '-'
  return `${textHash(r.skin)}|${r.variant}|${cape}|${r.view ?? 'front'}`
}

type Viewer = import('skinview3d').SkinViewer

const cache = new Map<string, Promise<string | null>>()
let chain: Promise<unknown> = Promise.resolve()
let viewer: Viewer | null = null
let idleTimer: ReturnType<typeof setTimeout> | null = null

function remember(key: string, value: Promise<string | null>) {
  cache.set(key, value)
  if (cache.size > MAX_CACHE) {
    const oldest = cache.keys().next().value
    if (oldest !== undefined) cache.delete(oldest)
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('Textur konnte nicht geladen werden'))
    img.src = src
  })
}

/** Erstes Bild eines senkrechten Umhang-Streifens (64:32-Raster je Bild). */
async function firstCapeFrame(src: string, frames: number): Promise<HTMLCanvasElement | string> {
  if (frames <= 1) return src
  const img = await loadImage(src)
  const width = img.naturalWidth
  const height = Math.floor(img.naturalHeight / frames)
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  canvas.getContext('2d')?.drawImage(img, 0, 0, width, height, 0, 0, width, height)
  return canvas
}

async function ensureViewer(): Promise<Viewer> {
  if (viewer) return viewer
  const { SkinViewer } = await import('skinview3d')
  const canvas = document.createElement('canvas')
  const next = new SkinViewer({
    canvas,
    width: THUMB_WIDTH,
    height: THUMB_HEIGHT,
    pixelRatio: PIXEL_RATIO,
    preserveDrawingBuffer: true,
    renderPaused: true,
    enableControls: false,
    fov: 40,
    zoom: 0.92,
  })
  next.animation = null
  // Licht wie in der großen Vorschau (Tag).
  next.globalLight.intensity = 2.6
  next.cameraLight.intensity = 0.7
  viewer = next
  return next
}

function scheduleDispose() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    idleTimer = null
    // Nur wenn nichts mehr in der Schlange steht (die Kette hängt sonst noch dran).
    void chain.then(() => {
      if (idleTimer) return
      viewer?.dispose()
      viewer = null
    })
  }, IDLE_MS)
}

async function draw(r: ThumbRequest): Promise<string | null> {
  const v = await ensureViewer()
  await v.loadSkin(r.skin, { model: r.variant === 'slim' ? 'slim' : 'default' })
  if (r.cape) await v.loadCape(await firstCapeFrame(r.cape, r.capeFrames ?? 1))
  else v.resetCape()
  const player = v.playerObject
  // Leicht gedreht, Arme etwas abgespreizt – eine ruhige „Karten-Pose“.
  player.rotation.set(0, r.view === 'back' ? Math.PI + 0.55 : 0.55, 0)
  player.skin.rightArm.rotation.set(0, 0, 0.1)
  player.skin.leftArm.rotation.set(0, 0, -0.1)
  player.skin.rightLeg.rotation.set(0.12, 0, 0)
  player.skin.leftLeg.rotation.set(-0.12, 0, 0)
  if (r.cape) player.cape.rotation.x = r.view === 'back' ? 0.18 : 0.08
  // Etwas von oben, wie auf einem Regal.
  v.camera.position.set(0, 7, 60)
  v.camera.lookAt(0, -1, 0)
  v.render()
  return v.canvas.toDataURL('image/png')
}

/**
 * Rendert (oder liefert aus dem Cache) ein Vorschaubild. Nie parallel – die
 * Aufträge laufen nacheinander durch dieselbe Szene. `null` = ging nicht (kein WebGL).
 */
export function renderSkinThumb(r: ThumbRequest): Promise<string | null> {
  const key = thumbKey(r)
  const known = cache.get(key)
  if (known) return known
  const job = chain.then(async () => {
    try {
      return await draw(r)
    } catch (e) {
      console.warn('Vorschaubild nicht möglich', e)
      return null
    } finally {
      scheduleDispose()
    }
  })
  chain = job
  remember(key, job)
  return job
}

/** Reaktives Vorschaubild für eine Karte; `null` solange es rendert (oder ohne Textur). */
export function useSkinThumb(source: () => ThumbRequest | null) {
  const src = ref<string | null>(null)
  let token = 0
  watch(
    () => {
      const r = source()
      return r ? { key: thumbKey(r), r } : null
    },
    async (next, prev) => {
      if (next?.key === prev?.key) return
      const mine = ++token
      src.value = null
      if (!next) return
      const url = await renderSkinThumb(next.r)
      if (mine === token) src.value = url
    },
    { immediate: true },
  )
  return src
}
