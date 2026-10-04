<script setup lang="ts">
import type { PresetCheck, PresetInput, PresetItem, PresetPickItem } from '~/types'
import type { PresetTemplateId } from '~/utils/presetTemplates'

// Preset-Editor als ganze Seite: links die Suche (wie „Entdecken“), rechts der Inhalt
// mit Abhängigkeiten und Konflikten. Name, Symbol/Farbe, „Immer automatisch“ und
// Speichern/Abbrechen bleiben oben (am Handy: Leiste unten) immer sichtbar.
const props = defineProps<{ presetId: string | null; template?: PresetTemplateId | null }>()

const router = useRouter()
const presets = usePresetsStore()
const toasts = useToasts()
const mobile = mobileUi

const ready = ref(false)
const missing = ref(false)
const name = ref('')
const auto = ref(false)
const icon = ref<string>('presets')
const color = ref<string>('redstone')
const items = ref<PresetItem[]>([])
const error = ref<string | null>(null)
const saving = ref(false)
const lookOpen = ref(false)
const pickOpen = ref(false)
const contentsOpen = ref(false)

const check = ref<PresetCheck | null>(null)
const checking = ref(false)
const conflictCount = computed(() => check.value?.conflicts.length ?? 0)

function fill(input: PresetInput) {
  name.value = input.name
  auto.value = input.auto
  items.value = structuredClone(toRaw(input.items))
  icon.value = input.icon ?? 'presets'
  color.value = input.color ?? 'redstone'
}

onMounted(async () => {
  try {
    await presets.load()
  } catch (e) {
    error.value = errorMessage(e)
  }
  if (props.presetId) {
    const preset = presets.items.find((p) => p.id === props.presetId && !p.builtin)
    if (preset) fill(preset)
    else missing.value = true
  } else if (props.template) {
    fill(templateInput(props.template))
  }
  ready.value = true
})

// Abhängigkeiten + Konflikte neu prüfen, sobald sich der Inhalt ändert (entprellt).
let checkTimer: ReturnType<typeof setTimeout> | undefined
let checkNo = 0
watch(
  () => items.value.map(presetItemKey).join('|'),
  () => {
    clearTimeout(checkTimer)
    checkTimer = setTimeout(runCheck, 600)
  },
)
async function runCheck() {
  const mine = ++checkNo
  if (!items.value.length) {
    check.value = null
    return
  }
  checking.value = true
  try {
    const result = await backend.presetCheck(toRaw(items.value))
    if (mine === checkNo) check.value = result
  } catch {
    // Ohne Netz gibt es eben keine Hinweise – das Preset bleibt bearbeitbar.
    if (mine === checkNo) check.value = null
  } finally {
    if (mine === checkNo) checking.value = false
  }
}
onBeforeUnmount(() => clearTimeout(checkTimer))

function add(item: PresetItem) {
  if (items.value.some((i) => presetItemKey(i) === presetItemKey(item))) return
  if (items.value.length >= PRESET_ITEMS_MAX) {
    toasts.error(t('presets.editor.tooManyItems', { max: PRESET_ITEMS_MAX }))
    return
  }
  items.value.push(item)
}

function remove(item: PresetItem) {
  items.value = items.value.filter((i) => presetItemKey(i) !== presetItemKey(item))
}

function takeOver(picked: PresetPickItem[]) {
  pickOpen.value = false
  const result = mergePicks(items.value, picked)
  items.value = result.items
  toasts.ok(t('presets.pick.done', { added: result.added, skipped: result.duplicates + result.overflow }, result.added))
  if (result.overflow) toasts.error(t('presets.editor.tooManyItems', { max: PRESET_ITEMS_MAX }))
}

async function save() {
  error.value = null
  const parsed = presetInputSchema.safeParse({ name: name.value, auto: auto.value, items: items.value, icon: icon.value, color: color.value })
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  saving.value = true
  try {
    const saved = props.presetId ? await presets.update(props.presetId, parsed.data) : await presets.create(parsed.data)
    toasts.ok(t('presets.editor.saved', { name: saved.name }))
    router.push('/presets')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

function cancel() {
  router.push('/presets')
}
</script>

<template>
  <div class="flex h-full flex-col mobile:h-auto">
    <!-- Kopf: Zurück, Aussehen, Name, „Immer automatisch“, Speichern -->
    <header class="flex items-center gap-3 border-b border-base-800 px-6 py-3 mobile:flex-wrap mobile:px-4">
      <!-- Am Handy hat die obere Leiste schon „Zurück“. -->
      <button v-if="!mobile" type="button" class="btn-icon rounded-full" :aria-label="t('common.actions.back')" @click="cancel">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m15 18-6-6 6-6" /></svg>
      </button>
      <button type="button" class="group relative rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-redstone-500" :aria-label="t('presets.look.title')" :title="t('presets.look.title')" data-testid="preset-look" @click="lookOpen = true">
        <PresetBadge :icon="icon" :color="color" :size="44" />
        <span class="absolute -right-1 -bottom-1 grid size-5 place-items-center rounded-full bg-base-700 text-base-50 ring-2 ring-base-950 group-hover:bg-redstone-500">
          <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.4"><path :d="icons.edit" /></svg>
        </span>
      </button>
      <div class="min-w-0 flex-1">
        <p class="text-[11px] text-base-400">{{ presetId ? t('presets.editor.editTitle') : t('presets.editor.newTitle') }}</p>
        <input
          v-model="name"
          class="w-full max-w-md truncate rounded-md border border-transparent bg-transparent px-1 py-0.5 -ml-1 text-xl font-semibold tracking-tight outline-none hover:border-base-700 focus:border-redstone-500 focus:bg-base-900"
          :maxlength="PRESET_NAME_MAX"
          :placeholder="t('presets.editor.namePlaceholder')"
          :aria-label="t('common.labels.name')"
          data-testid="preset-name"
        />
      </div>
      <label class="flex shrink-0 items-center gap-2 text-sm text-base-200 mobile:order-last mobile:w-full mobile:justify-between" :title="t('presets.autoHint')">
        {{ t('presets.autoLabel') }}
        <ToggleSwitch v-model="auto" :label="t('presets.autoLabel')" />
      </label>
      <div v-if="!mobile" class="flex shrink-0 gap-2">
        <button type="button" class="btn btn-ghost" @click="cancel">{{ t('common.actions.cancel') }}</button>
        <button type="button" class="btn btn-primary" :disabled="saving || !ready || missing" data-testid="preset-save" @click="save">{{ t('common.actions.save') }}</button>
      </div>
    </header>
    <p v-if="error" role="alert" class="mx-6 mt-3 rounded-lg border border-redstone-600/50 px-4 py-2 text-sm text-redstone-300 mobile:mx-4">{{ error }}</p>

    <RedstoneEmpty v-if="missing" class="mt-10" :seed="0x13" :title="t('presets.editor.missing')">
      <NuxtLink to="/presets" class="btn btn-ghost">{{ t('presets.page.title') }}</NuxtLink>
    </RedstoneEmpty>

    <div v-else class="flex min-h-0 flex-1 gap-5 px-6 pt-4 mobile:px-4">
      <div class="min-w-0 flex-1 overflow-y-auto pr-1 pb-6 mobile:overflow-visible mobile:pr-0 mobile:pb-24">
        <PresetBrowser :items="items" :full="mobile" @add="add" @remove="remove" />
      </div>
      <aside v-if="!mobile" class="w-[23rem] shrink-0 overflow-y-auto pb-6" :aria-label="t('presets.editor.contents', items.length)">
        <div class="card p-4">
          <PresetContents :items="items" :check="check" :checking="checking" @remove="remove" @import="pickOpen = true" />
        </div>
      </aside>
    </div>

    <!-- Handy: Inhalt + Speichern in einer Leiste unten -->
    <div v-if="mobile && !missing" class="sticky bottom-0 z-10 flex gap-2 border-t border-base-800 bg-base-950/95 px-4 py-3 backdrop-blur">
      <button type="button" class="btn btn-ghost flex-1 justify-center" @click="contentsOpen = true">
        {{ t('presets.editor.contents', items.length) }}
        <span v-if="conflictCount" class="rounded-full bg-warn px-1.5 text-[11px] leading-4 font-bold text-base-950">{{ conflictCount }}</span>
      </button>
      <button type="button" class="btn btn-primary flex-1 justify-center" :disabled="saving || !ready" @click="save">{{ t('common.actions.save') }}</button>
    </div>
    <MobileSheet v-if="mobile && contentsOpen" full :title="t('presets.editor.contents', items.length)" @close="contentsOpen = false">
      <PresetContents :items="items" :check="check" :checking="checking" :heading="false" @remove="remove" @import="contentsOpen = false; pickOpen = true" />
      <template #actions>
        <button type="button" class="btn btn-primary flex-1" @click="contentsOpen = false">{{ t('common.actions.done') }}</button>
      </template>
    </MobileSheet>

    <PresetLookDialog
      v-if="lookOpen"
      :icon="icon"
      :color="color"
      :name="name"
      @close="lookOpen = false"
      @pick="(look) => { icon = look.icon; color = look.color; lookOpen = false }"
    />
    <PresetPickDialog v-if="pickOpen" :items="items" @close="pickOpen = false" @pick="takeOver" />
  </div>
</template>
