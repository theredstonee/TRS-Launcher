<script setup lang="ts">
// Kürbis neben dem Logo (Halloween 2026): 12×12-Pixel-Sprite mit warmem Schein. Nur sichtbar mit html.theme-halloween.
const cv = useTemplateRef<HTMLCanvasElement>('cv')
onMounted(() => {
  if (cv.value) drawSprite(cv.value, 'pumpkin')
})
</script>

<template>
  <canvas ref="cv" class="hw-pumpkin" width="12" height="12" aria-hidden="true" />
</template>
