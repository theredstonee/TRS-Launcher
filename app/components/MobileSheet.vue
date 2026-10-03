<script setup lang="ts">
// Handy: Bottom-Sheet ohne Fußleiste (Menü „Mehr“, Filter, Aktionen per langem Druck).
// Nach unten wegwischen, auf den Hintergrund tippen oder Zurück-Taste schließt.
withDefaults(defineProps<{ title?: string; full?: boolean }>(), { title: '', full: false })
const emit = defineEmits<{ close: [] }>()

useOverlay(() => emit('close'))
const swipe = useSheetSwipe(() => emit('close'))
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50 flex items-end bg-black/60 pt-[calc(var(--safe-top)+1.5rem)]" @mousedown.self="emit('close')">
      <section
        role="dialog"
        aria-modal="true"
        :aria-label="title || undefined"
        class="flex max-h-full w-full animate-sheet flex-col rounded-t-2xl border border-b-0 border-base-800 bg-base-850 shadow-2xl transition-transform"
        :class="{ 'h-full': full }"
        :style="swipe.style.value"
      >
        <header class="relative shrink-0 touch-none px-5 pt-5 pb-3" v-on="swipe.handlers">
          <span class="absolute top-2 left-1/2 h-1 w-10 -translate-x-1/2 rounded-full bg-base-700" aria-hidden="true" />
          <div class="flex min-h-6 items-center gap-2">
            <h2 v-if="title" class="min-w-0 flex-1 truncate font-semibold">{{ title }}</h2>
            <slot name="header" />
          </div>
        </header>
        <div class="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-[calc(1rem+var(--safe-bottom))]">
          <slot />
        </div>
        <footer v-if="$slots.actions" class="flex shrink-0 flex-wrap justify-end gap-2 border-t border-base-800 px-4 py-3 pb-[calc(0.75rem+var(--safe-bottom))]">
          <slot name="actions" />
        </footer>
      </section>
    </div>
  </Teleport>
</template>
