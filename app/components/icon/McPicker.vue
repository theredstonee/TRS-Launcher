<script lang="ts">
// Einmal gelesene Texturen bleiben für die Sitzung im Speicher.
const textureCache = new Map<string, { key: string; dataUrl: string }[]>()
let versionCache: string[] | null = null
</script>

<script setup lang="ts">
import { matchesTexture, textureLabel, type GridSize, type PixelGrid } from '~/utils/iconEditor'
import { gridFromImage, loadImage } from '~/utils/iconRender'

// Minecraft-Items und -Blöcke als Motiv. Die Texturen liefert der Launcher NICHT mit: Sie kommen zur
// Laufzeit aus dem Spiel, das der Spieler schon geladen hat (Kern: icon_editor).
const props = defineProps<{ selected?: string | null }>()
const emit = defineEmits<{ pick: [value: { grid: PixelGrid; key: string }] }>()

const PAGE = 240
const versions = ref<string[] | null>(versionCache)
const version = ref<string | null>(versionCache?.[0] ?? null)
const textures = ref<{ key: string; dataUrl: string }[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const query = ref('')
const kind = ref<'all' | 'item' | 'block'>('all')
const limit = ref(PAGE)

const filtered = computed(() =>
  textures.value.filter((tex) => (kind.value === 'all' || tex.key.startsWith(`${kind.value}/`)) && matchesTexture(tex.key, query.value)),
)
const visible = computed(() => filtered.value.slice(0, limit.value))
watch([query, kind], () => (limit.value = PAGE))

async function loadVersions() {
  try {
    // Leere Liste nicht merken: nach dem ersten Spielstart sollen die Texturen ohne Neustart erscheinen.
    const list = versionCache ?? (await backend.mcTextureVersions())
    if (list.length) versionCache = list
    versions.value = list
    version.value ??= versions.value[0] ?? null
  } catch {
    // Ohne Kern (oder Fehler beim Lesen) gibt es eben keine Texturen – der Hinweis erklärt es.
    versions.value = []
  }
}

async function loadTextures(id: string) {
  const cached = textureCache.get(id)
  if (cached) {
    textures.value = cached
    return
  }
  loading.value = true
  error.value = null
  try {
    const set = await backend.mcTextures(id)
    textureCache.set(set.version, set.textures)
    if (version.value === id) textures.value = set.textures
  } catch (e) {
    error.value = errorMessage(e)
    textures.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadVersions)
watch(version, (id) => id && void loadTextures(id), { immediate: true })

async function pick(tex: { key: string; dataUrl: string }) {
  try {
    const img = await loadImage(tex.dataUrl)
    const size: GridSize = img.naturalWidth > 16 ? 32 : 16
    emit('pick', { grid: await gridFromImage(tex.dataUrl, size), key: tex.key })
  } catch (e) {
    error.value = errorMessage(e)
  }
}
</script>

<template>
  <div>
    <div v-if="versions === null" class="grid grid-cols-8 gap-1.5 sm:grid-cols-10">
      <div v-for="i in 30" :key="i" class="skeleton aspect-square" />
    </div>

    <!-- Noch kein Spiel geladen: keine Texturen -->
    <div v-else-if="!versions.length" class="rounded-lg border border-dashed border-base-700 px-4 py-8 text-center">
      <p class="text-sm text-base-200">{{ t('iconEditor.mc.noClient') }}</p>
      <p class="mt-2 text-xs text-base-400">{{ t('iconEditor.mc.licence') }}</p>
    </div>

    <template v-else>
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <input v-model="query" type="search" class="field min-w-40 flex-1" maxlength="64" :placeholder="t('iconEditor.mc.search')" :aria-label="t('iconEditor.mc.search')" />
        <div class="flex gap-1 rounded-lg bg-base-900 p-1 text-xs" role="radiogroup" :aria-label="t('iconEditor.mc.filter.label')">
          <button v-for="k in (['all', 'item', 'block'] as const)" :key="k" type="button" class="seg rounded-md" :class="{ 'seg-on': kind === k }" role="radio" :aria-checked="kind === k" @click="kind = k">
            {{ t(`iconEditor.mc.filter.${k}`) }}
          </button>
        </div>
        <select v-if="versions.length > 1" v-model="version" class="field w-auto py-1.5 text-xs" :aria-label="t('iconEditor.mc.versionLabel')">
          <option v-for="v in versions" :key="v" :value="v">{{ t('iconEditor.mc.version', { version: v }) }}</option>
        </select>
      </div>

      <p v-if="error" role="alert" class="mb-2 text-sm text-redstone-300">{{ error }}</p>
      <div v-if="loading" class="flex items-center gap-3 py-6 text-sm text-base-400">
        <RedstoneWire class="w-40" indeterminate :segments="16" />
        {{ t('iconEditor.mc.loading') }}
      </div>
      <p v-else-if="!filtered.length" class="py-6 text-center text-sm text-base-400">{{ t('iconEditor.mc.empty') }}</p>
      <div v-else class="grid max-h-80 grid-cols-[repeat(auto-fill,minmax(2.75rem,1fr))] gap-1.5 overflow-y-auto pr-1">
        <button
          v-for="tex in visible"
          :key="tex.key"
          type="button"
          class="grid aspect-square place-items-center rounded-md border bg-base-900 p-1 transition-colors hover:border-redstone-500 hover:bg-base-850"
          :class="props.selected === tex.key ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-800'"
          :title="textureLabel(tex.key)"
          :aria-label="textureLabel(tex.key)"
          @click="pick(tex)"
        >
          <img :src="tex.dataUrl" alt="" class="size-8" style="image-rendering: pixelated" draggable="false" />
        </button>
      </div>
      <div class="mt-2 flex items-center justify-between gap-3 text-xs text-base-400">
        <span>{{ t('iconEditor.mc.licence') }}</span>
        <button v-if="filtered.length > limit" type="button" class="btn btn-ghost shrink-0 px-2.5 py-1 text-xs" @click="limit += PAGE">
          {{ t('iconEditor.mc.more', { n: filtered.length - limit }) }}
        </button>
      </div>
    </template>
  </div>
</template>
