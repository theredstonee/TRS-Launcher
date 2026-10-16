<script setup lang="ts">
import { milestoneColor, type Milestone } from '~/utils/creator'

// Fan-Symbol eines Creators: senkrechter Streifen aus quadratischen Bildern
// (Frame = floor(jetzt / frameMs) % frames – für alle gleich, wie im Spiel),
// pixelig skaliert auf `px`. Ein Meilenstein legt einen farbigen Rahmen herum.
const props = withDefaults(
  defineProps<{
    image: string | null
    frames?: number
    frameMs?: number
    /** Anzeigegröße in CSS-Pixeln. */
    px?: number
    milestone?: Milestone | null
  }>(),
  { frames: 1, frameMs: 150, px: 16, milestone: null },
)

const frame = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function tick() {
  frame.value = trsFrameIndex(Date.now(), props.frames, props.frameMs)
}

function restart() {
  if (timer) clearInterval(timer)
  timer = null
  tick()
  // Animation nur, wenn sie gewollt ist (Einstellung „Animationen“ setzt data-reduced-motion).
  const reduced = typeof document !== 'undefined' && document.documentElement.hasAttribute('data-reduced-motion')
  if (props.frames > 1 && !reduced) timer = setInterval(tick, Math.max(50, props.frameMs))
}

watch(() => [props.frames, props.frameMs, props.image], restart)
onMounted(restart)
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

const style = computed(() => ({
  width: `${props.px}px`,
  height: `${props.px}px`,
  backgroundImage: props.image ? `url(${props.image})` : undefined,
  backgroundSize: `${props.px}px ${props.px * props.frames}px`,
  backgroundPosition: `0 ${-frame.value * props.px}px`,
  boxShadow: props.milestone ? `0 0 0 ${Math.max(1, Math.round(props.px / 12))}px ${milestoneColor[props.milestone]}` : undefined,
}))
</script>

<template>
  <span
    class="inline-block shrink-0 bg-no-repeat align-middle [image-rendering:pixelated]"
    :class="[image ? '' : 'rounded-sm bg-base-800', milestone ? 'rounded-[2px]' : '']"
    :style="style"
    role="img"
    :aria-label="milestone ? t('creator.symbol.withMilestone', { milestone: t(`creator.milestones.${milestone}`) }) : t('creator.symbol.label')"
  />
</template>
