<script setup lang="ts">
// Icon einer Mod bzw. eines Modrinth-Projekts. Erlaubt sind nur Modrinths CDN
// und Data-URLs, die der Launcher selbst aus der Datei erzeugt hat.
const props = withDefaults(defineProps<{ src: string | null | undefined; name: string; size?: number }>(), { size: 40 })

const safe = computed(() => {
  const s = props.src
  if (!s) return null
  return s.startsWith('https://cdn.modrinth.com/') || s.startsWith('data:image/png;base64,') ? s : null
})
const failed = ref(false)
watch(safe, () => (failed.value = false))
</script>

<template>
  <span
    class="relative block shrink-0 overflow-hidden bg-base-800 ring-1 ring-white/5"
    :class="size >= 64 ? 'rounded-xl' : 'rounded-lg'"
    :style="{ width: `${size}px`, height: `${size}px` }"
  >
    <img v-if="safe && !failed" :src="safe" alt="" loading="lazy" class="size-full object-cover" draggable="false" @error="failed = true" />
    <PixelIdenticon v-else :seed="name" color="var(--color-base-400)" />
  </span>
</template>
