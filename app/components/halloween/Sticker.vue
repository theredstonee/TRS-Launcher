<script setup lang="ts">
// Chat-Sticker (Halloween 2026, 12×12 Pixel ×8) – Bild aus dem Sticker-Katalog des Kerns.
const props = withDefaults(defineProps<{ id: string; size?: number }>(), { size: 96 })
const halloween = useHalloweenStore()
onMounted(() => void halloween.loadStickers())
const sticker = computed(() => halloween.stickers?.find((s) => s.id === props.id) ?? null)
const name = computed(() => (sticker.value ? localText(sticker.value.name, currentLocale.value) || props.id : props.id))
</script>

<template>
  <span class="hw-sticker" :style="{ width: `${size}px`, height: `${size}px` }" :title="name" :data-testid="`sticker-${id}`">
    <img v-if="sticker?.image" :src="sticker.image" :alt="name" draggable="false">
    <span v-else class="grid size-full place-items-center rounded-lg bg-base-900/60 text-xs text-base-400">{{ name }}</span>
  </span>
</template>

<style scoped>
.hw-sticker {
  display: inline-block;
}
.hw-sticker img {
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
  transition: transform 0.2s;
}
.hw-sticker:hover img {
  transform: rotate(-4deg) scale(1.06);
}
:root[data-reduced-motion] .hw-sticker:hover img {
  transform: none;
}
</style>
