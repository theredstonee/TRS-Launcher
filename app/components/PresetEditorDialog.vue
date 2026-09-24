<script setup lang="ts">
import type { ContentKind, ModrinthHit, Preset, PresetItem } from '~/types'

// Eigenes Preset anlegen oder bearbeiten: Name, „immer automatisch“ und
// Projekte aus der Modrinth-Suche (wie auf der Entdecken-Seite).
const props = defineProps<{ preset: Preset | null }>()
const emit = defineEmits<{ close: []; saved: [preset: Preset] }>()

const presets = usePresetsStore()
const name = ref(props.preset?.name ?? '')
const auto = ref(props.preset?.auto ?? false)
const items = ref<PresetItem[]>(structuredClone(toRaw(props.preset?.items ?? [])))
const error = ref<string | null>(null)
const saving = ref(false)

// --- Suche ---------------------------------------------------------------------
const kinds: ContentKind[] = ['mod', 'resourcepack', 'shaderpack']
const kind = ref<ContentKind>('mod')
const query = ref('')
const hits = ref<ModrinthHit[]>([])
const searching = ref(false)
const searchError = ref<string | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

async function search() {
  const mine = ++generation
  searching.value = true
  searchError.value = null
  try {
    const result = await backend.modrinthSearch({
      query: query.value.trim().slice(0, 100),
      kind: kind.value,
      gameVersions: [],
      loaders: [],
      categories: [],
      categoryMatch: 'all',
      excludeCategories: [],
      environments: [],
      excludeProjectIds: [],
      openSource: false,
      index: query.value.trim() ? 'relevance' : 'downloads',
      offset: 0,
      limit: 12,
    })
    if (mine === generation) hits.value = result.hits
  } catch (e) {
    if (mine === generation) searchError.value = errorMessage(e)
  } finally {
    if (mine === generation) searching.value = false
  }
}

watch([query, kind], () => {
  clearTimeout(timer)
  timer = setTimeout(search, 300)
})
onMounted(search)
onBeforeUnmount(() => clearTimeout(timer))

const added = computed(() => new Set(items.value.map((i) => i.projectId)))

function add(hit: ModrinthHit) {
  if (added.value.has(hit.projectId)) return
  if (items.value.length >= PRESET_ITEMS_MAX) {
    error.value = t('presets.editor.tooManyItems', { max: PRESET_ITEMS_MAX })
    return
  }
  items.value.push({
    source: 'modrinth',
    projectId: hit.projectId,
    title: hit.title.slice(0, 100) || hit.slug,
    iconUrl: hit.iconUrl,
    kind: kind.value,
  })
}

function remove(index: number) {
  items.value.splice(index, 1)
}

async function save() {
  error.value = null
  const parsed = presetInputSchema.safeParse({ name: name.value, auto: auto.value, items: items.value })
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  saving.value = true
  try {
    const saved = props.preset ? await presets.update(props.preset.id, parsed.data) : await presets.create(parsed.data)
    emit('saved', saved)
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseDialog :title="preset ? t('presets.editor.editTitle') : t('presets.editor.newTitle')" wide @close="emit('close')">
    <form id="preset-editor" class="space-y-4" @submit.prevent="save">
      <div class="flex items-end gap-4">
        <div class="flex-1">
          <label class="label" for="pe-name">{{ t('common.labels.name') }}</label>
          <input id="pe-name" v-model="name" class="field" :maxlength="PRESET_NAME_MAX" :placeholder="t('presets.editor.namePlaceholder')" autofocus />
        </div>
        <label class="mb-2 flex items-center gap-2 text-xs text-base-400">
          <ToggleSwitch v-model="auto" :label="t('presets.autoLabel')" />
          {{ t('presets.autoLabel') }}
        </label>
      </div>

      <div class="grid gap-4 md:grid-cols-2">
        <!-- Inhalt des Presets -->
        <section>
          <h3 class="label">{{ t('presets.editor.contents', items.length) }}</h3>
          <ul v-if="items.length" class="max-h-72 space-y-1 overflow-y-auto pr-1">
            <li v-for="(item, i) in items" :key="item.projectId" class="flex items-center gap-2.5 rounded-md bg-base-900 px-2 py-1.5">
              <ModIcon :src="item.iconUrl" :name="item.title" :size="28" />
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm">{{ item.title }}</span>
                <span class="block text-[11px] text-base-400">{{ contentKindLabel(item.kind) }}</span>
              </span>
              <button type="button" class="btn-icon size-7" :aria-label="t('presets.editor.remove', { title: item.title })" @click="remove(i)">
                <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><path :d="icons.close" /></svg>
              </button>
            </li>
          </ul>
          <p v-else class="rounded-md border border-dashed border-base-700 px-3 py-6 text-center text-xs text-base-400">{{ t('presets.editor.empty') }}</p>
        </section>

        <!-- Suche -->
        <section>
          <div class="mb-1.5 flex gap-1">
            <button
              v-for="k in kinds"
              :key="k"
              type="button"
              class="rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors"
              :class="kind === k ? 'bg-redstone-500 text-white' : 'bg-base-800 text-base-400 hover:text-base-50'"
              @click="kind = k"
            >
              {{ contentKindLabel(k) }}
            </button>
          </div>
          <input v-model="query" class="field mb-2 h-9" maxlength="100" :placeholder="t('presets.editor.searchPlaceholder')" :aria-label="t('presets.editor.searchLabel')" spellcheck="false" />
          <p v-if="searchError" class="text-xs text-redstone-300">{{ searchError }}</p>
          <ul class="max-h-60 space-y-1 overflow-y-auto pr-1" :aria-busy="searching">
            <li v-for="hit in hits" :key="hit.projectId" class="flex items-center gap-2.5 rounded-md px-1.5 py-1 hover:bg-base-900">
              <ModIcon :src="hit.iconUrl" :name="hit.title" :size="28" />
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm">{{ hit.title }}</span>
                <span class="block truncate text-[11px] text-base-400">{{ hit.description }}</span>
              </span>
              <button
                type="button"
                class="btn shrink-0 px-2 py-1 text-xs"
                :class="added.has(hit.projectId) ? 'btn-ghost' : 'btn-primary'"
                :disabled="added.has(hit.projectId)"
                @click="add(hit)"
              >
                {{ added.has(hit.projectId) ? t('presets.editor.added') : t('presets.editor.add') }}
              </button>
            </li>
            <li v-if="!searching && !hits.length && !searchError" class="px-2 py-4 text-center text-xs text-base-400">{{ t('presets.editor.noResults') }}</li>
          </ul>
          <p class="mt-2 text-[11px] leading-relaxed text-base-600">{{ t('presets.editor.hint') }}</p>
        </section>
      </div>

      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button type="submit" form="preset-editor" class="btn btn-primary" :disabled="saving">{{ t('common.actions.save') }}</button>
    </template>
  </BaseDialog>
</template>
