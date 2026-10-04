<script setup lang="ts">
import type { PresetTemplateId } from '~/utils/presetTemplates'

// „Neues Preset“: leer anfangen oder mit einer Vorlage (danach frei bearbeitbar).
const emit = defineEmits<{ close: []; pick: [template: PresetTemplateId | null] }>()
</script>

<template>
  <BaseDialog :title="t('presets.templates.title')" wide @close="emit('close')">
    <p class="mb-3 text-sm text-base-400">{{ t('presets.templates.intro') }}</p>
    <ul class="grid gap-2 sm:grid-cols-2">
      <li>
        <button type="button" class="card card-hover flex h-full w-full items-start gap-3 p-3 text-left" data-testid="preset-template-empty" @click="emit('pick', null)">
          <span class="grid size-11 shrink-0 place-items-center rounded-lg border border-dashed border-base-600 text-base-400">
            <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2"><path :d="icons.plus" /></svg>
          </span>
          <span class="min-w-0">
            <span class="block font-medium">{{ t('presets.templates.empty.name') }}</span>
            <span class="block text-xs leading-snug text-base-400">{{ t('presets.templates.empty.description') }}</span>
          </span>
        </button>
      </li>
      <li v-for="id in presetTemplateIds" :key="id">
        <button type="button" class="card card-hover flex h-full w-full items-start gap-3 p-3 text-left" :data-testid="`preset-template-${id}`" @click="emit('pick', id)">
          <PresetBadge :icon="presetTemplates[id].icon" :color="presetTemplates[id].color" :size="44" />
          <span class="min-w-0 flex-1">
            <span class="block font-medium">{{ t(`presets.templates.${id}.name`) }}</span>
            <span class="block text-xs leading-snug text-base-400">{{ t(`presets.templates.${id}.description`) }}</span>
            <span class="mt-2 flex -space-x-1.5">
              <ModIcon v-for="item in presetTemplates[id].items.slice(0, 6)" :key="item.projectId" :src="item.iconUrl" :name="item.title" :size="20" class="ring-2 ring-base-900" />
            </span>
          </span>
        </button>
      </li>
    </ul>
    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
    </template>
  </BaseDialog>
</template>
