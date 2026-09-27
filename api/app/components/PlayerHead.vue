<script setup lang="ts">
// Kopf eines Spielers: 8×8-Gesicht + Hut-Ebene aus dem Skin (textures.minecraft.net, per CSS zugeschnitten).
// Öffentliche Seiten geben die Skin-Adresse direkt mit (`skin`). Im Team-Bereich sammelt die Komponente alle
// Köpfe einer Seite und fragt sie gebündelt bei `POST /v1/admin/heads` ab (gespeicherte Skins, fehlende werden
// nach und nach bei Mojang nachgeschlagen). Bis dahin – oder ohne Skin – der farbige Anfangsbuchstabe.
const props = withDefaults(defineProps<{ uuid: string, name?: string | null, size?: number, fetch?: boolean, skin?: string | null }>(), { name: null, size: 40, fetch: true, skin: undefined })
const { api } = useAdmin()

const SKIN_URL = /^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{1,128}$/
const cache = useState<Record<string, string | null>>('admin-skin-cache', () => ({}))
const failed = ref(false)

const url = computed<string | null>(() => {
  if (props.skin !== undefined) return typeof props.skin === 'string' && SKIN_URL.test(props.skin) ? props.skin : null
  const v = cache.value[props.uuid]
  return typeof v === 'string' && SKIN_URL.test(v) ? v : null
})

watch(() => [props.uuid, props.skin, props.fetch] as const, ([u, skin, fetch]) => {
  failed.value = false
  if (skin !== undefined || !fetch || !import.meta.client || !/^[0-9a-f]{32}$/.test(u)) return
  if (u in cache.value) return
  queueHead(u, api, cache.value)
}, { immediate: true })

// Hut-Ebene nur zeigen, wenn sie Durchsichtiges enthält – wie Minecraft bei alten 64×32-Skins, deren Hut-Bereich
// oft komplett gefüllt ist (sonst wäre der Kopf z. B. schwarz). Bis das geprüft ist: nur das Gesicht.
const hat = ref(false)
watch(url, (u) => {
  hat.value = false
  if (!u || !import.meta.client) return
  void hatVisible(u).then((ok) => {
    if (url.value === u) hat.value = ok
  })
}, { immediate: true })

const initial = computed(() => (props.name || '?').slice(0, 1).toUpperCase())
const color = computed(() => {
  let h = 0
  for (const ch of props.uuid) h = (h * 31 + ch.charCodeAt(0)) % 360
  return `hsl(${h} 45% 36%)`
})
const scale = computed(() => props.size / 8)
</script>

<script lang="ts">
const hatCache = new Map<string, Promise<boolean>>()

/** Hat die Hut-Ebene (x 32–63, y 0–15) mindestens ein nicht deckendes Pixel? Fehler → Hut ausblenden. */
function hatVisible(url: string): Promise<boolean> {
  let p = hatCache.get(url)
  if (!p) {
    p = new Promise<boolean>((resolve) => {
      const img = new Image()
      img.crossOrigin = 'anonymous'
      img.referrerPolicy = 'no-referrer'
      img.onload = () => {
        try {
          const c = document.createElement('canvas')
          c.width = 32
          c.height = 16
          const ctx = c.getContext('2d', { willReadFrequently: true })
          if (!ctx) return resolve(false)
          const scale = img.naturalWidth / 64
          ctx.drawImage(img, 32 * scale, 0, 32 * scale, 16 * scale, 0, 0, 32, 16)
          const data = ctx.getImageData(0, 0, 32, 16).data
          for (let i = 3; i < data.length; i += 4) if (data[i]! < 255) return resolve(true)
          resolve(false)
        } catch {
          resolve(false)
        }
      }
      img.onerror = () => resolve(false)
      img.src = url
    })
    if (hatCache.size > 500) hatCache.clear()
    hatCache.set(url, p)
  }
  return p
}

// Gemeinsame Warteschlange aller Köpfe (nur im Browser): kurz sammeln, dann EIN Aufruf für bis zu 120 UUIDs.
type AdminApi = <T>(path: string, opts?: { method?: 'POST', body?: unknown }) => Promise<T>
const waiting = new Set<string>()
const asked = new Map<string, number>()
let timer: ReturnType<typeof setTimeout> | null = null
let running = false

function queueHead(uuid: string, api: AdminApi, cache: Record<string, string | null>, delay = 40) {
  if ((asked.get(uuid) ?? 0) >= 4) return
  waiting.add(uuid)
  if (!timer) timer = setTimeout(() => void flush(api, cache), delay)
}

async function flush(api: AdminApi, cache: Record<string, string | null>) {
  timer = null
  if (running) {
    timer = setTimeout(() => void flush(api, cache), 200)
    return
  }
  const batch = [...waiting].slice(0, 120)
  if (!batch.length) return
  for (const u of batch) {
    waiting.delete(u)
    asked.set(u, (asked.get(u) ?? 0) + 1)
  }
  running = true
  try {
    const res = await api<{ heads: Record<string, { url: string | null } | null>, pending: string[] }>('/v1/admin/heads', {
      method: 'POST',
      body: { uuids: batch },
    })
    for (const [u, h] of Object.entries(res.heads)) cache[u] = h?.url ?? null
    // Noch nicht nachgeschlagen: in ein paar Sekunden erneut (höchstens 4 Runden je Kopf).
    for (const u of res.pending) queueHead(u, api, cache, 2500)
  } catch {
    // Kein Team-Zugang oder Limit: Anfangsbuchstaben bleiben.
    for (const u of batch) if (!(u in cache)) cache[u] = null
  } finally {
    running = false
    if (waiting.size && !timer) timer = setTimeout(() => void flush(api, cache), 40)
  }
}
</script>

<template>
  <span class="head" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true">
    <template v-if="url && !failed">
      <img :src="url" alt="" referrerpolicy="no-referrer" class="layer" :style="{ width: `${64 * scale}px`, left: `${-8 * scale}px`, top: `${-8 * scale}px` }" @error="failed = true" />
      <img v-if="hat" :src="url" alt="" referrerpolicy="no-referrer" class="layer" :style="{ width: `${64 * scale}px`, left: `${-40 * scale}px`, top: `${-8 * scale}px` }" />
    </template>
    <span v-else class="fallback" :style="{ background: color, fontSize: `${Math.max(11, size * 0.45)}px` }">{{ initial }}</span>
  </span>
</template>

<style scoped>
.head {
  position: relative;
  display: inline-block;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 0.3rem;
  background: var(--color-base-950);
}
.layer {
  position: absolute;
  max-width: none;
  height: auto;
  image-rendering: pixelated;
}
.fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  font-family: var(--font-display);
  color: #fff;
}
</style>
