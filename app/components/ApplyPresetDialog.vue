<script setup lang="ts">
import type { Instance } from '~/types'

// „Preset anwenden“ für eine bestehende Instanz – läuft als Hintergrund-Aufgabe.
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ close: [] }>()

const selected = ref<string[]>([])
const tasks = useTasksStore()
const running = computed(() => tasks.isRunning(presetsTaskKey(props.instance.id)))

function apply() {
  if (!selected.value.length || running.value) return
  void applyPresetsTask(props.instance, [...selected.value])
  emit('close')
}
</script>

<template>
  <BaseDialog :title="t('presets.apply.title')" @close="emit('close')">
    <p class="mb-3 text-sm text-base-400">
      {{ t('presets.apply.intro', { name: instance.name, version: instance.gameVersion, loader: loaderLabels[instance.loader.kind] }) }}
    </p>
    <PresetPicker v-model="selected" :loader="instance.loader.kind" @manage="emit('close')" />
    <p v-if="running" class="mt-2 text-xs text-lamp-300">{{ t('presets.apply.running') }}</p>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button type="button" class="btn btn-primary" :disabled="!selected.length || running" @click="apply">
        {{ t('presets.apply.install', selected.length) }}
      </button>
    </template>
  </BaseDialog>
</template>
