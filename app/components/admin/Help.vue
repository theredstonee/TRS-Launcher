<script setup lang="ts">
// Übersicht der Tastenkürzel im Team-Bereich (`?`).
const emit = defineEmits<{ close: [] }>()
const keys = [
  ['/', 'search'],
  ['j', 'next'],
  ['k', 'prev'],
  ['Enter', 'open'],
  ['a', 'accept'],
  ['r', 'reject'],
  ['x', 'select'],
  ['?', 'help'],
  ['Esc', 'close'],
] as const
</script>

<template>
  <BaseDialog :title="t('team.keys.title')" @close="emit('close')">
    <ul class="space-y-2 text-sm" data-testid="admin-help">
      <li v-for="[key, what] in keys" :key="key" class="flex items-center gap-3">
        <kbd class="min-w-12 rounded-md border border-base-700 bg-base-900 px-2 py-0.5 text-center font-mono text-xs text-base-50 shadow-[inset_0_-2px_0_rgb(0_0_0/0.35)]">{{ key }}</kbd>
        <span class="text-base-200">{{ t(`team.keys.${what}`) }}</span>
      </li>
    </ul>
    <p class="mt-4 text-xs text-base-400">{{ t('team.keys.hint') }}</p>
    <template #actions>
      <button class="btn btn-primary" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>
