<script setup lang="ts">
// Ein Pixel-Sprite aus dem Halloween-Entwurf (shared/halloween-art.ts) als Leinwand, scharf hochskaliert.
import type { SpriteName } from '#shared/halloween-art'

const props = defineProps<{ name: SpriteName }>()
const cv = useTemplateRef<HTMLCanvasElement>('cv')
onMounted(() => {
  if (cv.value) drawSprite(cv.value, props.name)
})
watch(() => props.name, (n) => {
  if (cv.value) drawSprite(cv.value, n)
})
</script>

<template>
  <canvas ref="cv" class="hw-sprite" aria-hidden="true" />
</template>

<style scoped>
.hw-sprite {
  display: block;
  image-rendering: pixelated;
}
</style>
