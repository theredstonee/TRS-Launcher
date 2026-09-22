<script setup lang="ts">
// Eine Einstellungszeile: fetter Titel + gedämpfte Beschreibung links,
// Bedienelement rechts (oder darunter mit `stacked`).
defineProps<{ title: string; description?: string; stacked?: boolean; danger?: boolean }>()
</script>

<template>
  <div class="border-b border-base-800 py-4 last:border-b-0" :class="stacked ? 'space-y-3' : 'flex items-center justify-between gap-6'">
    <div class="min-w-0">
      <h4 class="text-sm font-semibold" :class="danger ? 'text-redstone-300' : 'text-base-50'">{{ title }}</h4>
      <p v-if="description" class="mt-0.5 text-xs leading-relaxed text-base-400">{{ description }}</p>
      <slot name="description" />
    </div>
    <div :class="stacked ? '' : 'flex shrink-0 items-center gap-2'">
      <slot />
    </div>
  </div>
</template>
