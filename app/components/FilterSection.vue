<script setup lang="ts">
// Einklappbarer Abschnitt der Filterleiste (Entdecken-Seite).
const props = withDefaults(defineProps<{ title: string; count?: number; locked?: boolean; startOpen?: boolean }>(), {
  count: 0,
  locked: false,
  startOpen: true,
})
const open = ref(props.startOpen)
const id = useId()
</script>

<template>
  <section class="border-b border-base-800 py-3 last:border-b-0">
    <button
      class="flex w-full items-center gap-2 rounded-md px-1 py-0.5 text-left text-sm font-semibold text-base-50 hover:text-white"
      :aria-expanded="open"
      :aria-controls="id"
      @click="open = !open"
    >
      <span class="flex-1">{{ title }}</span>
      <svg v-if="locked" viewBox="0 0 24 24" class="size-3.5 text-base-400" fill="none" stroke="currentColor" stroke-width="2.4" aria-label="Gesperrt"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
      <span v-if="count" class="rounded-full bg-redstone-900 px-1.5 text-[11px] font-medium text-redstone-300 tabular-nums">{{ count }}</span>
      <svg viewBox="0 0 24 24" class="size-4 text-base-400 transition-transform" :class="{ '-rotate-90': !open }" fill="none" stroke="currentColor" stroke-width="2.4"><path d="m6 9 6 6 6-6" /></svg>
    </button>
    <div v-show="open" :id="id" class="mt-2">
      <slot />
    </div>
  </section>
</template>
