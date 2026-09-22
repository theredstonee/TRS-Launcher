<script setup lang="ts">
import type { Instance } from '~/types'

// Instanz-Bild oder – ohne eigenes Bild – ein Pixel-Muster in der Farbe des Modloaders.
const props = withDefaults(defineProps<{ instance: Pick<Instance, 'id' | 'name' | 'loader' | 'iconPath'>; size?: number }>(), {
  size: 48,
})

const src = computed(() => instanceIconSrc(props.instance))
const failed = ref(false)
watch(src, () => (failed.value = false))
</script>

<template>
  <span
    class="relative block shrink-0 overflow-hidden bg-base-800 ring-1 ring-white/5"
    :class="size >= 64 ? 'rounded-xl' : size >= 36 ? 'rounded-lg' : 'rounded-md'"
    :style="{ width: `${size}px`, height: `${size}px` }"
  >
    <img v-if="src && !failed" :src="src" alt="" class="size-full object-cover" draggable="false" @error="failed = true" />
    <PixelIdenticon v-else :seed="instance.id" :color="loaderColors[instance.loader.kind]" :letter="size >= 28 ? instance.name.charAt(0).toUpperCase() : null" />
  </span>
</template>
