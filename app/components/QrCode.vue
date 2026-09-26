<script setup lang="ts">
// QR-Code als SVG. Die Matrix berechnet der Kern (`qr_code`); hier wird nur
// gezeichnet – dunkle Module als ein einziger Pfad, mit Ruhezone.
const props = defineProps<{ text: string; size?: number }>()

const matrix = ref<{ size: number; modules: number[] } | null>(null)
const failed = ref(false)

watch(
  () => props.text,
  async (text) => {
    matrix.value = null
    failed.value = false
    try {
      matrix.value = await backend.qrCode(text)
    } catch {
      failed.value = true
    }
  },
  { immediate: true },
)

const QUIET = 2
const path = computed(() => {
  const m = matrix.value
  if (!m) return ''
  let d = ''
  for (let y = 0; y < m.size; y++) {
    for (let x = 0; x < m.size; x++) {
      if (m.modules[y * m.size + x]) d += `M${x + QUIET} ${y + QUIET}h1v1h-1z`
    }
  }
  return d
})
const box = computed(() => (matrix.value ? matrix.value.size + QUIET * 2 : 29))
</script>

<template>
  <div class="inline-flex items-center justify-center rounded-lg bg-white p-1.5" :style="{ width: `${size ?? 168}px`, height: `${size ?? 168}px` }">
    <svg v-if="matrix" :viewBox="`0 0 ${box} ${box}`" class="size-full" shape-rendering="crispEdges" role="img" :aria-label="t('logShare.qrLabel')">
      <path :d="path" fill="#111116" />
    </svg>
    <div v-else-if="!failed" class="skeleton size-full" />
    <span v-else class="text-center text-[11px] text-base-600">{{ t('logShare.qrFailed') }}</span>
  </div>
</template>
