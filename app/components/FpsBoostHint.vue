<script setup lang="ts">
import type { Instance } from '~/types'
import type { FpsTier } from '~/utils/presets'

// Dezenter Hinweis auf der Instanzseite: Instanz mit Modloader, aber ohne
// Sodium/Embeddium/OptiFine (und kein Modpack) → FPS-Boost mit Stufenwahl
// anbieten. Einmal weggeklickt, bleibt er für diese Instanz weg.
const props = defineProps<{ instance: Instance }>()

const presets = usePresetsStore()
const tasks = useTasksStore()
const show = ref(false)
const tier = ref<FpsTier>('fpsBoost')
const running = computed(() => tasks.isRunning(presetsTaskKey(props.instance.id)))

const storageKey = computed(() => `trs.fpsHint.dismissed.${props.instance.id}`)

function dismissed(): boolean {
  try {
    return localStorage.getItem(storageKey.value) === '1'
  } catch {
    return false
  }
}

function dismiss() {
  show.value = false
  try {
    localStorage.setItem(storageKey.value, '1')
  } catch {
    // Ohne Speicher erscheint der Hinweis beim nächsten Mal wieder – nicht schlimm.
  }
}

async function check() {
  show.value = false
  if (dismissed()) return
  try {
    show.value = await backend.fpsBoostSuggested(props.instance.id)
  } catch {
    show.value = false
  }
}

onMounted(check)
// Loader gewechselt oder Inhalte geändert (Preset-Aufgabe fertig): neu prüfen.
watch(() => [props.instance.loader.kind, props.instance.gameVersion], check)
watch(running, (now, before) => {
  if (before && !now) void check()
})

async function apply() {
  if (running.value) return
  try {
    await presets.load()
  } catch (e) {
    useToasts().error(e)
    return
  }
  const id = tierPresetId(presets.items, tier.value)
  if (!id) return
  show.value = false
  void applyPresetsTask(props.instance, [id])
}
</script>

<template>
  <section
    v-if="show"
    class="card mb-4 flex flex-col gap-3 border-lamp-400/30 px-4 py-3"
    :aria-label="t('presets.hint.title')"
  >
    <div class="flex items-start gap-3">
      <span class="grid size-8 shrink-0 place-items-center rounded-lg bg-redstone-900 text-redstone-300 ring-1 ring-redstone-600/40" aria-hidden="true">
        <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><path :d="icons.trs" /></svg>
      </span>
      <div class="min-w-0 flex-1">
        <p class="text-sm font-medium text-base-50">{{ t('presets.hint.title') }}</p>
        <p class="text-xs text-base-400">{{ t('presets.hint.text') }}</p>
      </div>
      <button class="btn-icon size-7 shrink-0" :aria-label="t('presets.hint.dismiss')" :title="t('presets.hint.dismiss')" @click="dismiss">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 6l12 12M18 6 6 18" /></svg>
      </button>
    </div>
    <FpsTierPicker v-model="tier" :loader="instance.loader.kind" :disabled="running" />
    <div class="flex justify-end">
      <button type="button" class="btn btn-primary py-1.5 text-xs" :disabled="running" @click="apply">
        {{ running ? t('presets.apply.installing') : t('presets.hint.apply') }}
      </button>
    </div>
  </section>
</template>
