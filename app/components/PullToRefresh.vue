<script setup lang="ts">
// Handy: „Zum Aktualisieren ziehen“. Als erstes Kind in den scrollenden Bereich setzen –
// ganz oben nach unten ziehen lädt neu. Am Desktop rendert es nichts Sichtbares.
const props = defineProps<{ refresh: () => unknown }>()

const mobile = mobileUi
const root = useTemplateRef<HTMLElement>('root')
const pull = ref(0)
const busy = ref(false)
let scroller: HTMLElement | null = null
let startY: number | null = null

function scrollParent(el: HTMLElement | null): HTMLElement | null {
  for (let p = el?.parentElement ?? null; p; p = p.parentElement) {
    const o = getComputedStyle(p).overflowY
    if (o === 'auto' || o === 'scroll') return p
  }
  return null
}

function onStart(e: TouchEvent) {
  if (busy.value || !scroller || scroller.scrollTop > 0 || e.touches.length !== 1) return
  startY = e.touches[0]!.clientY
}
function onMove(e: TouchEvent) {
  if (startY === null || !scroller) return
  const dy = e.touches[0]!.clientY - startY
  if (dy <= 0 || scroller.scrollTop > 0) {
    pull.value = 0
    return
  }
  pull.value = pullDistance(dy)
  // Nicht gleichzeitig die Seite verschieben.
  if (e.cancelable) e.preventDefault()
}
async function onEnd() {
  if (startY === null) return
  startY = null
  if (!pullTriggers(pull.value)) {
    pull.value = 0
    return
  }
  busy.value = true
  pull.value = PULL_HOLD
  try {
    await props.refresh()
  } catch {
    // Fehler meldet die Seite selbst (Toast); hier nur die Geste beenden.
  } finally {
    busy.value = false
    pull.value = 0
  }
}

function attach() {
  if (!mobile.value || scroller) return
  scroller = scrollParent(root.value)
  if (!scroller) return
  scroller.addEventListener('touchstart', onStart, { passive: true })
  scroller.addEventListener('touchmove', onMove, { passive: false })
  scroller.addEventListener('touchend', onEnd, { passive: true })
  scroller.addEventListener('touchcancel', onEnd, { passive: true })
}
function detach() {
  if (!scroller) return
  scroller.removeEventListener('touchstart', onStart)
  scroller.removeEventListener('touchmove', onMove)
  scroller.removeEventListener('touchend', onEnd)
  scroller.removeEventListener('touchcancel', onEnd)
  scroller = null
}
onMounted(attach)
watch(mobile, async (on) => {
  if (!on) return detach()
  await nextTick()
  attach()
})
onBeforeUnmount(detach)

const ready = computed(() => pullTriggers(pull.value))
</script>

<template>
  <div v-if="mobile" ref="root" class="pointer-events-none sticky top-0 z-20 h-0">
    <div
      v-if="pull > 0 || busy"
      class="absolute left-1/2 grid size-10 -translate-x-1/2 place-items-center rounded-full border bg-base-850 shadow-lg shadow-black/40 transition-colors"
      :class="ready || busy ? 'border-redstone-500 text-redstone-400' : 'border-base-700 text-base-400'"
      :style="{ top: `${pull - 44}px` }"
      role="img"
      :aria-label="t('mobile.pullToRefresh')"
      data-testid="pull-to-refresh"
    >
      <svg viewBox="0 0 24 24" class="size-5" :class="{ 'animate-spin': busy }" :style="busy ? undefined : { transform: `rotate(${pull * 4}deg)` }" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M20 12a8 8 0 1 1-2.34-5.66M20 4v4h-4" />
      </svg>
    </div>
  </div>
</template>
