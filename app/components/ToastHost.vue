<script setup lang="ts">
const toasts = useToasts()
</script>

<template>
  <TransitionGroup name="toast" tag="div" class="pointer-events-none fixed right-4 bottom-4 z-[60] flex w-80 flex-col gap-2" aria-live="polite">
    <div
      v-for="t in toasts.items"
      :key="t.id"
      class="pointer-events-auto flex items-start gap-2.5 rounded-md border bg-base-850 px-3.5 py-2.5 text-sm shadow-xl"
      :class="{
        'border-ok/40 text-base-50': t.kind === 'ok',
        'border-redstone-600/60 text-redstone-300': t.kind === 'error',
        'border-base-700 text-base-200': t.kind === 'info',
      }"
      :role="t.kind === 'error' ? 'alert' : 'status'"
    >
      <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="{ 'bg-ok': t.kind === 'ok', 'bg-redstone-400': t.kind === 'error', 'bg-base-400': t.kind === 'info' }" />
      <p class="min-w-0 flex-1">{{ t.text }}</p>
      <button class="shrink-0 text-base-400 hover:text-base-50" aria-label="Schließen" @click="toasts.dismiss(t.id)">
        <svg viewBox="0 0 10 10" class="size-2.5"><path d="M0 0l10 10M10 0L0 10" stroke="currentColor" stroke-width="1.5" /></svg>
      </button>
    </div>
  </TransitionGroup>
</template>
