<script setup lang="ts">
// Schalter im Pillen-Stil (statt Checkbox) – für Einstellungszeilen.
const model = defineModel<boolean>({ required: true })
defineProps<{ label: string; disabled?: boolean }>()
</script>

<template>
  <button
    type="button"
    role="switch"
    :aria-checked="model"
    :aria-label="label"
    :disabled="disabled"
    class="relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-40"
    :class="model ? 'bg-redstone-500' : 'bg-base-700'"
    @click="model = !model"
  >
    <span class="absolute top-0.5 left-0.5 size-5 rounded-full bg-white shadow transition-transform" :class="{ 'translate-x-5': model }" />
  </button>
</template>
