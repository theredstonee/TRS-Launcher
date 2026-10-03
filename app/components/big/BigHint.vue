<script setup lang="ts">
import type { BigAction, NavDirection } from '~/utils/bigPicture'

// Knopf-Hinweis unten in der Big-Picture-Ansicht: Glyphe passend zum zuletzt
// benutzten Controller (Xbox, PlayStation, Nintendo, Steam Deck) oder zur Tastatur.
const props = defineProps<{ action: Exclude<BigAction, NavDirection>; label?: string }>()
const emit = defineEmits<{ press: [] }>()

const store = useBigPictureStore()
const glyph = computed(() => buttonGlyph(store.controller, props.action))
</script>

<template>
  <button type="button" tabindex="-1" class="hint" @click="emit('press')">
    <span class="glyph" :class="`glyph-${glyph.shape}`" :style="glyph.color ? { color: glyph.color, borderColor: glyph.color } : undefined">
      {{ glyph.label }}
    </span>
    <span v-if="label" class="text-base text-base-200">{{ label }}</span>
  </button>
</template>

<style scoped>
.hint {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.25rem 0.5rem;
  border-radius: 0.5rem;
  transition: background-color 0.15s ease;
}
.hint:hover {
  background: color-mix(in srgb, var(--color-base-700) 50%, transparent);
}
.glyph {
  display: inline-grid;
  place-items: center;
  min-width: 2rem;
  height: 2rem;
  padding: 0 0.45rem;
  border: 2px solid var(--color-base-400);
  color: var(--color-base-50);
  background: var(--color-base-900);
  font-weight: 700;
  font-size: 0.95rem;
  line-height: 1;
}
.glyph-face {
  border-radius: 9999px;
  width: 2rem;
  padding: 0;
}
.glyph-shoulder {
  border-radius: 0.65rem 0.65rem 0.35rem 0.35rem;
  min-width: 2.6rem;
}
.glyph-key {
  border-radius: 0.35rem;
  border-bottom-width: 4px;
  font-family: var(--font-mono);
  font-size: 0.85rem;
}
.glyph-menu {
  border-radius: 0.5rem;
  font-size: 0.8rem;
}
</style>
