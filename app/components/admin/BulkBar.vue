<script setup lang="ts">
// Leiste für Sammelaktionen: erscheint unten, sobald etwas ausgewählt ist.
defineProps<{ count: number; limit: number }>()
const emit = defineEmits<{ clear: [] }>()
</script>

<template>
  <Transition name="bulk">
    <div
      v-if="count"
      class="sticky bottom-4 z-20 mt-4 flex flex-wrap items-center gap-2 rounded-xl border border-redstone-600/50 bg-base-850/95 px-4 py-3 shadow-2xl shadow-black/40 backdrop-blur-sm"
      data-testid="bulk-bar"
    >
      <span class="text-sm font-semibold text-base-50">{{ t('team.common.selected', { n: count }) }}</span>
      <span v-if="count >= limit" class="text-xs text-lamp-300">{{ t('team.common.selectLimit', { n: limit }) }}</span>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="emit('clear')">{{ t('team.common.clearSelection') }}</button>
      <span class="flex-1" />
      <slot />
    </div>
  </Transition>
</template>

<style scoped>
.bulk-enter-active,
.bulk-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.bulk-enter-from,
.bulk-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
