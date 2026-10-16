<script setup lang="ts">
import type { LauncherPumpkin } from '~/utils/halloween'

// Versteckter Kürbis der Kürbis-Suche (Halloween 2026): 12×12 Pixel, ~55 % sichtbar, wächst
// beim Drüberfahren, platzt beim Klick (Toast „Kürbis gefunden! n/13“). Gefundene Kürbisse
// und alles außerhalb des Events werden gar nicht erst gezeigt.
const props = withDefaults(defineProps<{ id: LauncherPumpkin; size?: number }>(), { size: 14 })
const halloween = useHalloweenStore()
const popping = ref(false)
const shown = computed(() => halloween.visible(props.id) || popping.value)

async function pop(e: Event) {
  e.stopPropagation()
  e.preventDefault()
  if (popping.value) return
  popping.value = true
  const ok = await halloween.find(props.id)
  // Animation ausspielen lassen (0,8 s), dann verschwindet er (gefunden) – sonst wieder da.
  setTimeout(() => (popping.value = false), ok ? 800 : 0)
}
</script>

<template>
  <button
    v-if="shown"
    type="button"
    class="hw-pumpkin"
    :class="{ found: popping }"
    :style="{ width: `${size}px`, height: `${size}px` }"
    :aria-label="t('halloween.hunt.pumpkinLabel')"
    :data-testid="`pumpkin-${id}`"
    @click="pop"
    @mousedown.stop
  >
    <HalloweenPixelSprite name="pumpkin" class="size-full" />
  </button>
</template>

<style scoped>
.hw-pumpkin {
  padding: 0;
  border: 0;
  background: none;
  opacity: 0.55;
  cursor: pointer;
  transition: opacity 0.3s, transform 0.3s;
  pointer-events: auto;
}
.hw-pumpkin:hover,
.hw-pumpkin:focus-visible {
  opacity: 1;
  transform: scale(1.25);
  outline: none;
}
.hw-pumpkin.found {
  animation: hw-popout 0.8s forwards;
}
:root[data-reduced-motion] .hw-pumpkin {
  transition: none;
}
:root[data-reduced-motion] .hw-pumpkin.found {
  animation: none;
  opacity: 0;
}
@keyframes hw-popout {
  40% {
    transform: scale(2.2);
    opacity: 1;
    filter: drop-shadow(0 0 8px #ffd27a);
  }
  100% {
    transform: scale(0);
    opacity: 0;
  }
}
</style>
