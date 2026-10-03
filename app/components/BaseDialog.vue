<script setup lang="ts">
// `huge` = Werkstatt-Dialoge mit Vorschau (Umhang zuschneiden, Umhang prüfen).
// Am Handy: Bottom-Sheet über die ganze Breite, nach unten wegwischbar, Zurück-Taste schließt.
defineProps<{ title: string; wide?: boolean; huge?: boolean }>()
const emit = defineEmits<{ close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

useOverlay(() => emit('close'))
const swipe = useSheetSwipe(() => emit('close'))
const mobile = mobileUi
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6 mobile:items-end mobile:p-0 mobile:pt-[calc(var(--safe-top)+1.5rem)]" data-dialog-over-settings @mousedown.self="emit('close')">
    <section
      role="dialog"
      aria-modal="true"
      :aria-label="title"
      class="card w-full bg-base-850 shadow-2xl mobile:flex mobile:max-h-full mobile:max-w-none mobile:flex-col mobile:rounded-b-none mobile:rounded-t-2xl mobile:animate-sheet mobile:transition-transform"
      :class="huge ? 'flex max-h-full max-w-5xl flex-col' : wide ? 'max-w-2xl' : 'max-w-md'"
      :style="swipe.style.value"
    >
      <header class="border-b border-base-800 px-5 py-3.5 mobile:relative mobile:shrink-0 mobile:touch-none mobile:pt-5" v-on="mobile ? swipe.handlers : {}">
        <span v-if="mobile" class="absolute top-2 left-1/2 h-1 w-10 -translate-x-1/2 rounded-full bg-base-700" aria-hidden="true" />
        <h2 class="font-semibold">{{ title }}</h2>
      </header>
      <div class="px-5 py-4 mobile:min-h-0 mobile:flex-1 mobile:overflow-y-auto mobile:overscroll-contain" :class="{ 'min-h-0 overflow-y-auto': huge }">
        <slot />
      </div>
      <footer class="flex justify-end gap-2 border-t border-base-800 px-5 py-3 mobile:shrink-0 mobile:flex-wrap mobile:pb-[calc(0.75rem+var(--safe-bottom))]">
        <slot name="actions" />
      </footer>
    </section>
  </div>
</template>
