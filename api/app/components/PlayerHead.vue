<script setup lang="ts">
// Kopf eines Spielers: 8×8-Gesicht + Hut-Ebene aus dem Skin (textures.minecraft.net, per CSS
// zugeschnitten). Skins holt `/v1/admin/users/{uuid}/skin` (gecacht, 30/min) – in langen Listen
// `fetch = false`, dann nur der farbige Anfangsbuchstabe.
const props = withDefaults(defineProps<{ uuid: string, name?: string | null, size?: number, fetch?: boolean }>(), { name: null, size: 40, fetch: true })
const { api } = useAdmin()

const SKIN_URL = /^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{1,128}$/
const cache = useState<Record<string, string | null>>('admin-skin-cache', () => ({}))
const url = ref<string | null>(null)
const failed = ref(false)

async function load() {
  url.value = null
  failed.value = false
  const u = props.uuid
  if (!props.fetch || !/^[0-9a-f]{32}$/.test(u)) return
  if (u in cache.value) {
    url.value = cache.value[u] ?? null
    return
  }
  try {
    const res = await api<{ textureUrl: string | null }>(`/v1/admin/users/${u}/skin`)
    const v = typeof res.textureUrl === 'string' && SKIN_URL.test(res.textureUrl) ? res.textureUrl : null
    cache.value[u] = v
    if (props.uuid === u) url.value = v
  } catch (e) {
    if ((e as { statusCode?: number }).statusCode === 404) cache.value[u] = null
  }
}
watch(() => props.uuid, load, { immediate: true })

const initial = computed(() => (props.name || '?').slice(0, 1).toUpperCase())
const color = computed(() => {
  let h = 0
  for (const ch of props.uuid) h = (h * 31 + ch.charCodeAt(0)) % 360
  return `hsl(${h} 45% 36%)`
})
const scale = computed(() => props.size / 8)
</script>

<template>
  <span class="head" :style="{ width: `${size}px`, height: `${size}px` }" aria-hidden="true">
    <template v-if="url && !failed">
      <img :src="url" alt="" referrerpolicy="no-referrer" class="layer" :style="{ width: `${64 * scale}px`, left: `${-8 * scale}px`, top: `${-8 * scale}px` }" @error="failed = true" />
      <img :src="url" alt="" referrerpolicy="no-referrer" class="layer" :style="{ width: `${64 * scale}px`, left: `${-40 * scale}px`, top: `${-8 * scale}px` }" />
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
