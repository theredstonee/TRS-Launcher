<script setup lang="ts">
import { drawSprite, spriteSize, type SpriteName } from '~/utils/moonnight/sprites'

// Ein Pixel-Sprite der Mondnacht (Kürbis, Fledermaus, Mond-Fledermaus …) als scharfes Canvas.
const props = defineProps<{ name: SpriteName }>()
const canvas = useTemplateRef<HTMLCanvasElement>('canvas')
const size = computed(() => spriteSize(props.name))
function paint() {
  if (canvas.value) drawSprite(canvas.value, props.name)
}
onMounted(paint)
watch(() => props.name, () => nextTick(paint))
</script>

<template>
  <canvas ref="canvas" class="pixel-sprite" :width="size.w" :height="size.h" aria-hidden="true" />
</template>

<style scoped>
.pixel-sprite {
  display: block;
  image-rendering: pixelated;
}
</style>
