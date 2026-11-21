<script setup lang="ts">
// Vorschau eines Partner-Umhangs: Maße/Frames aus `GET /v1/capes/{id}`, dann die Vorderseite wie im Launcher.
const props = withDefaults(defineProps<{ id: string, width?: number }>(), { width: 56 })
interface CapeMeta { url: string, scale: number, frames: number, frameTimeMs: number | null }
const meta = ref<CapeMeta | null>(null)
onMounted(async () => {
  try {
    meta.value = (await apiFetch<{ cape: CapeMeta }>(`/v1/capes/${encodeURIComponent(props.id)}`)).cape
  } catch {
    meta.value = null
  }
})
</script>

<template>
  <CapeThumb v-if="meta" :texture="meta.url" :scale="meta.scale" :frames="meta.frames" :frame-time-ms="meta.frameTimeMs" :width="width" />
  <span v-else class="block rounded-sm bg-base-800" :style="{ width: `${width}px`, height: `${Math.round((width * 16) / 10)}px` }" />
</template>
