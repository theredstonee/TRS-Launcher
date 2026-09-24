<script setup lang="ts">
import type { PresetItemOutcome } from '~/types'

// Bericht nach dem Installieren von Presets: was kam dazu, was nicht – und warum.
const presets = usePresetsStore()
const current = computed(() => presets.report)
const summary = computed(() => (current.value ? summarizePresetReport(current.value.report) : null))
const installed = computed(() => current.value?.report.items.filter((i) => presetItemOk(i)) ?? [])

function chip(item: PresetItemOutcome): { text: string; cls: string } {
  switch (item.status) {
    case 'installed':
      return { text: item.versionNumber ?? t('presets.status.installed'), cls: 'bg-redstone-900 text-redstone-300' }
    case 'alreadyInstalled':
      return { text: t('presets.status.alreadyInstalled'), cls: 'bg-base-800 text-base-400' }
    case 'duplicate':
      return { text: t('presets.status.duplicate'), cls: 'bg-base-800 text-base-400' }
    default:
      return { text: t('presets.status.skipped'), cls: 'bg-lamp-900 text-lamp-300' }
  }
}

function openInstance() {
  const id = current.value?.instance.id
  presets.closeReport()
  if (id) navigateTo(`/instances/${id}`)
}
</script>

<template>
  <BaseDialog v-if="current && summary" :title="t('presets.report.title', { name: current.instance.name })" wide @close="presets.closeReport()">
    <p class="mb-1 text-sm text-base-50">{{ presetSummaryText(current.report) }}</p>
    <p class="mb-4 text-xs text-base-400">
      {{ t('presets.report.target', { version: current.report.gameVersion, loader: loaderLabels[current.report.loader] }) }}
      <template v-if="current.report.dependencies"> · {{ t('presets.report.dependencies', current.report.dependencies) }}</template>
    </p>
    <p v-if="current.report.shaderPack" class="mb-4 rounded-md border border-lamp-400/30 bg-lamp-900 px-3 py-2 text-xs text-lamp-300" role="note">
      {{ t('presets.report.shaderOn', { name: current.report.shaderPack }) }}
    </p>

    <div class="max-h-[55vh] space-y-4 overflow-y-auto pr-1">
      <section v-if="summary.problems.length">
        <h3 class="mb-1.5 text-xs font-semibold text-lamp-300">{{ t('presets.report.skipped', summary.problems.length) }}</h3>
        <ul class="space-y-1.5">
          <li v-for="(item, i) in summary.problems" :key="`p${i}`" class="flex items-center gap-3 rounded-md border border-lamp-400/30 bg-base-900 px-3 py-2">
            <ModIcon :src="item.iconUrl" :name="item.title" :size="28" />
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm text-base-50">{{ item.title }}</p>
              <p class="text-xs text-base-400">
                {{ presetReason(item, current.report) }}
                <template v-if="item.error"> ({{ userErrorText(item.error) }})</template>
              </p>
            </div>
          </li>
        </ul>
      </section>

      <section v-if="installed.length">
        <h3 class="mb-1.5 text-xs font-semibold text-base-400">{{ t('presets.report.included', installed.length) }}</h3>
        <ul class="space-y-1">
          <li v-for="(item, i) in installed" :key="`o${i}`" class="flex items-center gap-3 px-1 py-1">
            <ModIcon :src="item.iconUrl" :name="item.title" :size="24" />
            <span class="min-w-0 flex-1 truncate text-sm">{{ item.title }}</span>
            <span class="badge font-mono" :class="chip(item).cls">{{ chip(item).text }}</span>
          </li>
        </ul>
      </section>

      <details v-if="summary.quiet.length" class="text-xs text-base-400">
        <summary class="cursor-pointer select-none hover:text-base-50">{{ t('presets.report.notNeeded', summary.quiet.length) }}</summary>
        <p class="mt-1.5 leading-relaxed">{{ summary.quiet.map((i) => i.title).join(' · ') }}</p>
      </details>
    </div>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="openInstance">{{ t('tasks.toast.openInstance') }}</button>
      <button type="button" class="btn btn-primary" @click="presets.closeReport()">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>
