<script setup lang="ts">
import type { PresetCheck, PresetItem } from '~/types'

// „Inhalt“ des Preset-Editors: Einträge nach Art, je Eintrag die Pflicht-Abhängigkeiten,
// die automatisch mitkommen, und bekannte Konflikte als Warnung.
const props = withDefaults(defineProps<{ items: PresetItem[]; check: PresetCheck | null; checking: boolean; heading?: boolean }>(), { heading: true })
const emit = defineEmits<{ remove: [item: PresetItem]; import: [] }>()

const groups = computed(() => groupPresetItems(props.items))
const conflicts = computed(() => props.check?.conflicts ?? [])

function loaderText(loaders: string[]): string {
  return loaders.map((l) => loaderNames[l] ?? l).join(', ')
}
function sourceLabel(item: PresetItem): string {
  return t(`browse.source.${item.source}`)
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="flex items-center gap-2">
      <h2 v-if="heading" class="font-semibold">{{ t('presets.editor.contents', items.length) }}</h2>
      <span v-if="checking" class="text-[11px] text-base-400" role="status">{{ t('presets.editor.checking') }}</span>
      <span class="ml-auto text-[11px] text-base-600 tabular-nums">{{ items.length }}/{{ PRESET_ITEMS_MAX }}</span>
    </div>

    <button type="button" class="btn btn-ghost w-full justify-center" data-testid="preset-import" @click="emit('import')">
      <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.import" /></svg>
      {{ t('presets.pick.open') }}
    </button>

    <!-- Bekannte Konflikte -->
    <ul v-if="conflicts.length" class="space-y-1.5" :aria-label="t('presets.editor.conflictsLabel')">
      <li v-for="(c, i) in conflicts" :key="i" class="flex gap-2 rounded-lg border border-warn/40 bg-warn/10 px-3 py-2 text-xs leading-snug text-warn">
        <svg viewBox="0 0 24 24" class="mt-px size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4 2.5 20h19zM12 10v4M12 17h.01" /></svg>
        <span>{{ conflictText(c) }}</span>
      </li>
    </ul>

    <p v-if="!items.length" class="rounded-lg border border-dashed border-base-700 px-3 py-8 text-center text-xs leading-relaxed text-base-400">
      {{ t('presets.editor.empty') }}
    </p>

    <section v-for="g in groups" :key="g.kind">
      <h3 class="mb-1.5 flex items-center gap-2 text-[11px] font-semibold tracking-wide text-base-400 uppercase">
        {{ contentKindLabel(g.kind) }}
        <span class="rounded-full bg-base-800 px-1.5 text-[10px] text-base-200 tabular-nums">{{ g.items.length }}</span>
      </h3>
      <ul class="space-y-1">
        <li
          v-for="item in g.items"
          :key="presetItemKey(item)"
          class="rounded-lg bg-base-900 px-2 py-1.5 ring-1"
          :class="conflictsOf(check, item).length ? 'ring-warn/50' : 'ring-base-800'"
        >
          <div class="flex items-center gap-2.5">
            <ModIcon :src="item.iconUrl" :name="item.title" :size="32" />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm">{{ item.title }}</span>
              <span class="block text-[11px] text-base-400">{{ sourceLabel(item) }}</span>
            </span>
            <button type="button" class="btn-icon size-8 mobile:size-11" :aria-label="t('presets.editor.remove', { title: item.title })" :title="t('presets.editor.remove', { title: item.title })" @click="emit('remove', item)">
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><path :d="icons.close" /></svg>
            </button>
          </div>
          <!-- Abhängigkeiten, die beim Installieren automatisch mitkommen -->
          <div v-if="depsOf(check, item, items).length" class="mt-1.5 flex flex-wrap items-center gap-1 pl-[42px]">
            <span class="text-[10px] text-base-600">{{ t('presets.editor.brings') }}</span>
            <span
              v-for="d in depsOf(check, item, items)"
              :key="presetItemKey(d)"
              class="inline-flex max-w-full items-center gap-1 rounded-full bg-base-800 py-0.5 pr-2 pl-0.5 text-[10px] text-base-200"
              :title="d.loaders.length ? t('presets.editor.depOnly', { title: d.title, loaders: loaderText(d.loaders) }) : t('presets.editor.depAlways', { title: d.title })"
            >
              <ModIcon :src="d.iconUrl" :name="d.title" :size="14" />
              <span class="truncate">{{ d.title }}</span>
              <span v-if="d.loaders.length" class="shrink-0 text-base-400">· {{ loaderText(d.loaders) }}</span>
            </span>
          </div>
        </li>
      </ul>
    </section>

    <p class="text-[11px] leading-relaxed text-base-600">{{ t('presets.editor.hint') }}</p>
  </div>
</template>
