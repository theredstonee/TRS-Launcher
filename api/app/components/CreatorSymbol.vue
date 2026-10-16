<script setup lang="ts">
// Fan-Symbol (§35): senkrechter PNG-Streifen, je Frame size×size. Animiert über die Hintergrund-Position, pixelgenau
// skaliert – so sieht das Team das Symbol wie im Spiel (64 px Vorschau und 16 px wie vor dem Namen).
const props = withDefaults(defineProps<{ url: string, frames?: number, frameMs?: number, px?: number, label?: string }>(), {
  frames: 1,
  frameMs: 150,
  px: 64,
  label: '',
})

const frame = ref(0)
let timer: ReturnType<typeof setInterval> | null = null
function stop() {
  if (timer) clearInterval(timer)
  timer = null
}
function start() {
  stop()
  frame.value = 0
  if (props.frames > 1) timer = setInterval(() => (frame.value = (frame.value + 1) % props.frames), Math.max(50, props.frameMs))
}
onMounted(start)
onBeforeUnmount(stop)
watch(() => [props.frames, props.frameMs, props.url], start)

const style = computed(() => ({
  width: `${props.px}px`,
  height: `${props.px}px`,
  backgroundImage: `url("${props.url}")`,
  backgroundSize: `${props.px}px ${props.px * props.frames}px`,
  backgroundPosition: `0 -${frame.value * props.px}px`,
}))
</script>

<template>
  <span class="creator-symbol" role="img" :aria-label="label || undefined" :style="style" />
</template>

<style scoped>
.creator-symbol {
  display: inline-block;
  flex-shrink: 0;
  background-repeat: no-repeat;
  image-rendering: pixelated;
}
</style>
