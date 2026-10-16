<script setup lang="ts">
// Versteckter Kürbis der Kürbis-Suche (Halloween 2026, §36): winzig, halb durchsichtig, wächst beim Zeigen, platzt beim
// Klick. Ohne Anmeldung: Hinweis „Melde dich an, damit dein Fund zählt“. Schon gefundene erscheinen nicht mehr.
// Position bestimmt der Aufrufer (Klasse/Stil am Element).
const props = defineProps<{ id: string }>()

const hw = useHalloween()
const cv = useTemplateRef<HTMLCanvasElement>('cv')
const popping = ref(false)
const gone = ref(false)
const mounted = ref(false)

const found = computed(() => hw.me.value?.pumpkins.found.includes(props.id) ?? false)
const show = computed(() => mounted.value && hw.on.value && !found.value && !gone.value)

onMounted(() => {
  mounted.value = true
  if (!hw.meLoaded.value) void hw.refresh()
})
watch(cv, (c) => {
  if (c) drawSprite(c, 'pumpkin')
}, { flush: 'post' })

async function click() {
  if (popping.value) return
  const counted = await hw.findPumpkin(props.id)
  if (!counted) return
  popping.value = true
  setTimeout(() => {
    gone.value = true
  }, 800)
}
</script>

<template>
  <button v-if="show" type="button" class="hw-hunt" :class="{ popping }" :aria-label="hw.t.value.pumpkinLabel" @click.stop="click">
    <canvas ref="cv" width="12" height="12" aria-hidden="true" />
  </button>
</template>

<style scoped>
.hw-hunt {
  width: 14px;
  height: 14px;
  padding: 0;
  border: 0;
  background: none;
  opacity: 0.55;
  cursor: pointer;
  transition: opacity 0.3s, transform 0.3s;
  z-index: 5;
}
.hw-hunt:hover,
.hw-hunt:focus-visible {
  opacity: 1;
  transform: scale(1.25);
}
.hw-hunt canvas {
  display: block;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
}
.hw-hunt.popping {
  animation: popout 0.8s forwards;
  pointer-events: none;
}
@keyframes popout {
  40% { transform: scale(2.2); opacity: 1; filter: drop-shadow(0 0 8px #ffd27a); }
  100% { transform: scale(0); opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .hw-hunt.popping { animation: none; opacity: 0; }
}
</style>
