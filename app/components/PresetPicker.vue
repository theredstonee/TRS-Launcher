<script setup lang="ts">
import type { LoaderKind, Preset } from '~/types'

// Presets zum Ankreuzen (Neue Instanz, „Preset anwenden“). Vorausgewählt wird
// alles mit „immer automatisch“ – solange der Nutzer noch nichts geändert hat.
const props = withDefaults(
  defineProps<{
    loader: LoaderKind | null
    context?: 'instance' | 'modpack'
    /** Beim Laden die „immer automatisch“-Presets ankreuzen. */
    preselect?: boolean
  }>(),
  { context: 'instance', preselect: false },
)
const emit = defineEmits<{ 'use-fabric': []; manage: [] }>()
const selected = defineModel<string[]>({ required: true })

const presets = usePresetsStore()
const error = ref<string | null>(null)
const touched = ref(false)

const visible = computed(() => visiblePresets(presets.items, props.context))

onMounted(async () => {
  try {
    const list = await presets.load()
    if (props.preselect && !touched.value) {
      selected.value = defaultPresetSelection(list, { loader: props.loader, context: props.context })
    }
  } catch (e) {
    error.value = errorMessage(e)
  }
})

// Beim Wechsel auf Vanilla fällt FPS-Boost weg (dort übernimmt die TRS-Optimierung).
watch(
  () => props.loader,
  (loader, before) => {
    const disabled = new Set(presets.items.filter((p) => presetDisabled(p, loader)).map((p) => p.id))
    if (disabled.size) selected.value = selected.value.filter((id) => !disabled.has(id))
    // Zurück von Vanilla: Vorauswahl wiederherstellen, solange nichts von Hand geändert wurde.
    else if (before === 'vanilla' && props.preselect && !touched.value) {
      selected.value = defaultPresetSelection(presets.items, { loader, context: props.context })
    }
  },
)

function toggle(preset: Preset) {
  if (presetDisabled(preset, props.loader)) return
  touched.value = true
  selected.value = selected.value.includes(preset.id)
    ? selected.value.filter((id) => id !== preset.id)
    : [...selected.value, preset.id]
}

function summary(preset: Preset): string {
  return presetDescription(preset) ?? t('presets.itemCount', preset.items.length)
}

/** Vanilla + Mods gewählt: Hinweis, dass Mods einen Modloader brauchen. */
const needsLoader = computed(
  () =>
    props.loader === 'vanilla' &&
    presets.items.some((p) => selected.value.includes(p.id) && presetHasMods(p) && !presetDisabled(p, props.loader)),
)
</script>

<template>
  <div>
    <div class="mb-1.5 flex items-center justify-between">
      <span class="label mb-0">{{ t('presets.picker.title') }}</span>
      <NuxtLink to="/presets" class="text-xs text-base-400 hover:text-base-50" @click="emit('manage')">{{ t('presets.picker.manage') }}</NuxtLink>
    </div>
    <p v-if="error" class="text-xs text-redstone-300">{{ error }}</p>
    <div v-else-if="!presets.loaded" class="space-y-1.5">
      <div v-for="i in 3" :key="i" class="skeleton h-11" />
    </div>
    <ul v-else class="max-h-60 space-y-1.5 overflow-y-auto pr-0.5" role="group" :aria-label="t('presets.picker.title')">
      <li v-for="p in visible" :key="p.id">
        <button
          type="button"
          role="checkbox"
          :aria-checked="selected.includes(p.id)"
          :disabled="presetDisabled(p, loader)"
          class="flex w-full items-center gap-3 rounded-md border px-3 py-2 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-50"
          :class="selected.includes(p.id)
            ? 'border-redstone-500 bg-redstone-900'
            : 'border-base-700 bg-base-900 hover:border-base-600'"
          @click="toggle(p)"
        >
          <!-- Redstone-Lampe als Kästchen: an = leuchtet -->
          <span
            class="grid size-4 shrink-0 place-items-center rounded-[3px] border"
            :class="selected.includes(p.id) ? 'border-lamp-400 bg-lamp-400 shadow-[0_0_8px_var(--color-lamp-400)]' : 'border-base-600 bg-base-850'"
            aria-hidden="true"
          >
            <svg v-if="selected.includes(p.id)" viewBox="0 0 24 24" class="size-3 text-base-950" fill="none" stroke="currentColor" stroke-width="3.5"><path :d="icons.check" /></svg>
          </span>
          <span class="min-w-0 flex-1">
            <span class="flex items-center gap-1.5">
              <span class="truncate text-sm font-medium text-base-50">{{ presetName(p) }}</span>
              <span v-if="p.builtin" class="badge bg-base-800 text-base-400">TRS</span>
              <span v-if="p.auto" class="badge bg-lamp-900 text-lamp-300">{{ t('presets.auto') }}</span>
            </span>
            <span class="block truncate text-xs text-base-400">
              {{ presetDisabled(p, loader) ? t('presets.picker.vanillaBoost') : summary(p) }}
            </span>
          </span>
        </button>
      </li>
    </ul>
    <div v-if="needsLoader" class="mt-2 flex items-center gap-3 rounded-md border border-lamp-400/40 bg-lamp-900 px-3 py-2 text-xs text-lamp-300" role="note">
      <span class="flex-1">{{ t('presets.picker.needsLoader') }}</span>
      <button type="button" class="btn btn-ghost shrink-0 py-1 text-xs" @click="emit('use-fabric')">{{ t('presets.picker.useFabric') }}</button>
    </div>
  </div>
</template>
