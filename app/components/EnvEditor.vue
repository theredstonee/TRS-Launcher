<script setup lang="ts">
import type { EnvVar } from '~/types'

// Umgebungsvariablen als Schlüssel/Wert-Zeilen.
const model = defineModel<EnvVar[]>({ required: true })
defineProps<{ disabled?: boolean; placeholder?: EnvVar[] }>()

function add() {
  if (model.value.length < 32) model.value = [...model.value, { key: '', value: '' }]
}
function remove(i: number) {
  model.value = model.value.filter((_, j) => j !== i)
}
function update(i: number, field: keyof EnvVar, value: string) {
  model.value = model.value.map((v, j) => (j === i ? { ...v, [field]: value } : v))
}
</script>

<template>
  <div class="space-y-2">
    <div v-for="(v, i) in model" :key="i" class="flex items-center gap-2">
      <input
        :value="v.key"
        class="field w-56 font-mono text-xs"
        maxlength="64"
        placeholder="NAME"
        spellcheck="false"
        :disabled="disabled"
        :aria-label="t('envEditor.nameLabel', { n: i + 1 })"
        @input="update(i, 'key', ($event.target as HTMLInputElement).value)"
      />
      <span class="text-base-600">=</span>
      <input
        :value="v.value"
        class="field min-w-0 flex-1 font-mono text-xs"
        maxlength="1024"
        :placeholder="t('envEditor.valuePlaceholder')"
        spellcheck="false"
        :disabled="disabled"
        :aria-label="t('envEditor.valueLabel', { n: i + 1 })"
        @input="update(i, 'value', ($event.target as HTMLInputElement).value)"
      />
      <button type="button" class="btn-icon size-8 hover:text-redstone-300" :disabled="disabled" :aria-label="t('envEditor.remove', { name: v.key || i + 1 })" @click="remove(i)">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path :d="icons.close" /></svg>
      </button>
    </div>
    <p v-if="!model.length && placeholder?.length" class="font-mono text-xs text-base-600">
      {{ t('envEditor.global', { vars: placeholder.map((p) => `${p.key}=${p.value}`).join('  ') }) }}
    </p>
    <button type="button" class="btn btn-ghost py-1.5 text-xs" :disabled="disabled || model.length >= 32" @click="add">
      <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
      {{ t('envEditor.add') }}
    </button>
  </div>
</template>
