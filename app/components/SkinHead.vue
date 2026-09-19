<script setup lang="ts">
// Schneidet das Gesicht (8×8 bei 8/8) und die Hut-Ebene (8×8 bei 40/8) direkt
// aus der 64er-Skin-Textur – ohne Drittanbieter-Dienst.
const props = defineProps<{ skinUrl: string | null; name: string; size?: number }>()

const px = computed(() => props.size ?? 32)

// Das Backend lässt nur https://textures.minecraft.net durch; hier zur Sicherheit noch einmal.
const safeUrl = computed(() =>
  props.skinUrl?.startsWith('https://textures.minecraft.net/') ? props.skinUrl : null,
)

function layer(x: number, y: number) {
  const s = px.value
  return {
    backgroundImage: `url("${safeUrl.value}")`,
    backgroundSize: `${s * 8}px ${s * 8}px`,
    backgroundPosition: `-${(x / 8) * s}px -${(y / 8) * s}px`,
  }
}
</script>

<template>
  <div
    class="relative shrink-0 overflow-hidden rounded bg-base-800 [image-rendering:pixelated]"
    :style="{ width: `${px}px`, height: `${px}px` }"
    :aria-label="name"
    role="img"
  >
    <template v-if="safeUrl">
      <div class="absolute inset-0" :style="layer(8, 8)" />
      <div class="absolute inset-0" :style="layer(40, 8)" />
    </template>
    <span v-else class="flex size-full items-center justify-center font-mono text-xs font-bold text-base-400">
      {{ name.charAt(0).toUpperCase() }}
    </span>
  </div>
</template>
