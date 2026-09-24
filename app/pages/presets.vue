<script setup lang="ts">
import type { Preset } from '~/types'

// Mod-Presets verwalten: fertige TRS-Presets (nur „immer automatisch“) und
// eigene (bearbeiten, umbenennen, Reihenfolge, teilen, löschen).
const presets = usePresetsStore()
const toasts = useToasts()

const error = ref<string | null>(null)
const editing = ref<Preset | null>(null)
const creating = ref(false)
const confirmDelete = ref<string | null>(null)
const expanded = ref<Set<string>>(new Set())
const busy = ref<string | null>(null)

onMounted(async () => {
  try {
    await presets.load(true)
  } catch (e) {
    error.value = errorMessage(e)
  }
})

// Nvidium ohne passende Grafikkarte gar nicht erst anbieten.
const list = computed(() => presets.items.filter((p) => p.available))

function toggleExpanded(id: string) {
  const next = new Set(expanded.value)
  if (!next.delete(id)) next.add(id)
  expanded.value = next
}

async function run(id: string, work: () => Promise<unknown>) {
  busy.value = id
  try {
    await work()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

function setAuto(p: Preset, auto: boolean) {
  run(p.id, () => presets.setAuto(p.id, auto))
}

function move(p: Preset, delta: number) {
  run(p.id, () => presets.move(p.id, delta, list.value.map((x) => x.id)))
}

function exportPreset(p: Preset) {
  run(p.id, async () => {
    if (await presets.exportPreset(p.id)) toasts.ok(t('presets.page.exported', { name: presetName(p) }))
  })
}

function remove(p: Preset) {
  if (confirmDelete.value !== p.id) {
    confirmDelete.value = p.id
    return
  }
  confirmDelete.value = null
  run(p.id, async () => {
    await presets.remove(p.id)
    toasts.ok(t('presets.page.deleted', { name: presetName(p) }))
  })
}

async function importPreset() {
  error.value = null
  try {
    const preset = await presets.importPreset()
    if (preset) {
      toasts.ok(t('presets.page.imported', { name: preset.name }))
      expanded.value = new Set(expanded.value).add(preset.id)
    }
  } catch (e) {
    toasts.error(e)
  }
}

function kindsText(p: Preset): string {
  if (p.builtin) return presetDescription(p) ?? ''
  if (!p.items.length) return t('presets.page.emptyPreset')
  const counts = new Map<string, number>()
  for (const i of p.items) counts.set(contentKindLabel(i.kind), (counts.get(contentKindLabel(i.kind)) ?? 0) + 1)
  return [...counts].map(([label, n]) => `${n}× ${label}`).join(' · ')
}

const isFirst = (p: Preset) => list.value[0]?.id === p.id
const isLast = (p: Preset) => list.value.at(-1)?.id === p.id
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <PageHeader :title="t('presets.page.title')" :subtitle="t('presets.page.subtitle')">
      <button class="btn btn-ghost" @click="importPreset">{{ t('common.actions.import') }}</button>
      <button class="btn btn-primary" @click="creating = true">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path :d="icons.plus" /></svg>
        {{ t('presets.page.new') }}
      </button>
    </PageHeader>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="!presets.loaded && !error" class="space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-[74px]" />
    </div>

    <ul v-else class="space-y-2">
      <li v-for="p in list" :key="p.id" class="card card-hover">
        <div class="flex items-center gap-3 px-4 py-3">
          <!-- Symbol: TRS-Blitz für fertige Presets, sonst die ersten Icons -->
          <div class="relative size-10 shrink-0">
            <span v-if="p.builtin" class="grid size-10 place-items-center rounded-lg bg-redstone-900 text-redstone-300 ring-1 ring-redstone-600/40">
              <svg viewBox="0 0 24 24" class="size-5" fill="currentColor"><path :d="icons.trs" /></svg>
            </span>
            <ModIcon v-else :src="p.items[0]?.iconUrl" :name="p.name" :size="40" />
          </div>

          <button type="button" class="min-w-0 flex-1 text-left" :aria-expanded="expanded.has(p.id)" @click="toggleExpanded(p.id)">
            <span class="flex items-center gap-1.5">
              <span class="truncate font-medium text-base-50">{{ presetName(p) }}</span>
              <span v-if="p.builtin" class="badge bg-base-800 text-base-400">TRS</span>
              <span class="text-xs text-base-600">{{ t('presets.itemCount', p.items.length) }}</span>
            </span>
            <span class="block truncate text-xs text-base-400">{{ kindsText(p) }}</span>
          </button>

          <label class="flex shrink-0 items-center gap-2 text-xs text-base-400" :title="t('presets.autoHint')">
            {{ t('presets.auto') }}
            <ToggleSwitch :model-value="p.auto" :label="t('presets.autoLabel')" :disabled="busy === p.id" @update:model-value="setAuto(p, $event)" />
          </label>

          <div class="flex shrink-0 items-center gap-1">
            <button class="btn-icon size-8" :disabled="isFirst(p) || busy === p.id" :aria-label="t('presets.page.moveUp')" :title="t('presets.page.moveUp')" @click="move(p, -1)">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m6 15 6-6 6 6" /></svg>
            </button>
            <button class="btn-icon size-8" :disabled="isLast(p) || busy === p.id" :aria-label="t('presets.page.moveDown')" :title="t('presets.page.moveDown')" @click="move(p, 1)">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m6 9 6 6 6-6" /></svg>
            </button>
          </div>
        </div>

        <div v-if="expanded.has(p.id)" class="border-t border-base-800 px-4 py-3">
          <ul v-if="p.items.length" class="flex flex-wrap gap-1.5">
            <li v-for="item in p.items" :key="item.projectId" class="chip gap-1.5 py-0.5 pl-0.5">
              <ModIcon :src="item.iconUrl" :name="item.title" :size="20" />
              {{ item.title }}
            </li>
          </ul>
          <p v-else class="text-xs text-base-400">{{ t('presets.page.emptyPreset') }}</p>
          <p v-if="p.builtin" class="mt-2 text-xs text-base-600">{{ t('presets.page.builtinHint') }}</p>
          <div v-else class="mt-3 flex flex-wrap gap-2">
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="busy === p.id" @click="editing = p">{{ t('common.actions.edit') }}</button>
            <button class="btn btn-ghost py-1.5 text-xs" :disabled="busy === p.id || !p.items.length" @click="exportPreset(p)">{{ t('common.actions.export') }}</button>
            <button class="btn btn-danger ml-auto py-1.5 text-xs" :disabled="busy === p.id" @click="remove(p)" @blur="confirmDelete = null">
              {{ confirmDelete === p.id ? t('presets.page.confirmDelete') : t('common.actions.delete') }}
            </button>
          </div>
        </div>
      </li>
    </ul>

    <p class="mt-4 text-xs leading-relaxed text-base-600">{{ t('presets.page.footer') }}</p>

    <PresetEditorDialog v-if="creating" :preset="null" @close="creating = false" />
    <PresetEditorDialog v-if="editing" :preset="editing" @close="editing = null" />
  </div>
</template>
