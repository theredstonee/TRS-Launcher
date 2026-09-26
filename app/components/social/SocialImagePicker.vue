<script setup lang="ts">
import { convertFileSrc, isTauri } from '@tauri-apps/api/core'
import type { GalleryShot } from '~/types'
import { MAX_IMAGES, localImageUrl, type LocalImage } from '~/utils/chat'
import type { DraftImage } from '~/stores/chat'

// „Bilder auswählen [n/10]“: Screenshots aller Instanzen (Alle), markierte
// (Favoriten) und eigene Dateien (Uploads: Dateidialog, Drag & Drop, Einfügen).
// Vorschaubilder erzeugt der Kern; Pfade kommen nie ins Webview.
const selected = defineModel<DraftImage[]>({ required: true })
const emit = defineEmits<{ done: [] }>()
const toasts = useToasts()

type Filter = 'all' | 'favorites' | 'uploads'
const filter = ref<Filter>('all')
const filters: Filter[] = ['all', 'favorites', 'uploads']
const search = ref('')
const searching = ref(false)
/** Kachelgröße: 2 (groß) bis 6 (klein) Spalten. */
const columns = ref(3)
const shots = ref<GalleryShot[]>([])
const favorites = ref<Set<string>>(new Set())
const uploads = ref<LocalImage[]>([])
const thumbs = ref<Record<string, string>>({})
const loading = ref(true)
const busy = ref(false)

const shotKey = (s: GalleryShot) => `${s.instanceId}/${s.fileName}`

async function load() {
  loading.value = true
  try {
    const [all, favs, local] = await Promise.all([
      backend.allScreenshots().catch(() => [] as GalleryShot[]),
      backend.social.screenshotFavorites().catch(() => [] as string[]),
      backend.social.localImages().catch(() => [] as LocalImage[]),
    ])
    shots.value = all
    favorites.value = new Set(favs)
    uploads.value = local
  } finally {
    loading.value = false
  }
}

const shown = computed(() => {
  const q = search.value.trim().toLocaleLowerCase()
  const match = (s: string) => !q || s.toLocaleLowerCase().includes(q)
  if (filter.value === 'uploads') return uploads.value.filter((u) => match(u.name)).map((u) => ({ kind: 'local' as const, key: `l:${u.id}`, image: u }))
  const list = filter.value === 'favorites' ? shots.value.filter((s) => favorites.value.has(shotKey(s))) : shots.value
  return list
    .filter((s) => match(s.fileName) || match(s.instanceName))
    .map((s) => ({ kind: 'shot' as const, key: `s:${shotKey(s)}`, shot: s }))
})

type Entry = (typeof shown.value)[number]

function sourceOf(entry: Entry): DraftImage['source'] {
  return entry.kind === 'local'
    ? { kind: 'local', id: entry.image.id }
    : { kind: 'screenshot', instanceId: entry.shot.instanceId, fileName: entry.shot.fileName }
}

function previewOf(entry: Entry): string {
  return entry.kind === 'local' ? localImageUrl(entry.image.id) : (thumbs.value[shotKey(entry.shot)] ?? '')
}

function sameSource(a: DraftImage['source'], b: DraftImage['source']): boolean {
  if (a.kind === 'local' && b.kind === 'local') return a.id === b.id
  if (a.kind === 'screenshot' && b.kind === 'screenshot') return a.instanceId === b.instanceId && a.fileName === b.fileName
  return false
}

function indexOf(entry: Entry): number {
  const src = sourceOf(entry)
  return selected.value.findIndex((s) => sameSource(s.source, src))
}

function toggle(entry: Entry) {
  const i = indexOf(entry)
  if (i >= 0) {
    selected.value = selected.value.filter((_, j) => j !== i)
    return
  }
  if (selected.value.length >= MAX_IMAGES) {
    toasts.info(t('social.picker.limit', { max: MAX_IMAGES }))
    return
  }
  selected.value = [...selected.value, { source: sourceOf(entry), preview: previewOf(entry) }]
}

async function toggleFavorite(shot: GalleryShot) {
  const key = shotKey(shot)
  try {
    const list = await backend.social.setScreenshotFavorite(shot.instanceId, shot.fileName, !favorites.value.has(key))
    favorites.value = new Set(list)
  } catch (e) {
    toasts.error(e)
  }
}

/** Eigene Dateien hinzufügen und gleich auswählen. */
function addLocal(images: LocalImage[], rejected: number) {
  const known = new Set(uploads.value.map((u) => u.id))
  uploads.value = [...images.filter((i) => !known.has(i.id)), ...uploads.value]
  for (const image of images) {
    if (selected.value.length >= MAX_IMAGES) break
    if (!selected.value.some((s) => s.source.kind === 'local' && s.source.id === image.id)) {
      selected.value = [...selected.value, { source: { kind: 'local', id: image.id }, preview: localImageUrl(image.id) }]
    }
  }
  if (rejected) toasts.info(t('social.picker.rejected', rejected))
}

async function pickFiles() {
  if (busy.value) return
  busy.value = true
  try {
    const result = await backend.social.pickImages()
    if (result) {
      filter.value = 'uploads'
      addLocal(result.images, result.rejected)
    }
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

defineExpose({ addLocal })

// --- Vorschaubilder der Screenshots erst bei Sichtbarkeit ----------------------------

let observer: IntersectionObserver | null = null
const pending = new Set<string>()

async function loadThumb(instanceId: string, fileName: string) {
  const key = `${instanceId}/${fileName}`
  if (thumbs.value[key] || pending.has(key) || !isTauri()) return
  pending.add(key)
  try {
    const path = await backend.screenshotThumbnail(instanceId, fileName)
    if (path) {
      const url = convertFileSrc(path)
      thumbs.value = { ...thumbs.value, [key]: url }
      // Schon ausgewählt, bevor die Vorschau da war? Vorschau nachtragen.
      selected.value = selected.value.map((s) =>
        s.source.kind === 'screenshot' && s.source.instanceId === instanceId && s.source.fileName === fileName && !s.preview ? { ...s, preview: url } : s,
      )
    }
  } catch {
    // Ohne Vorschau bleibt die Kachel leer.
  } finally {
    pending.delete(key)
  }
}

function observe(el: unknown, entry: Entry) {
  if (!(el instanceof HTMLElement) || entry.kind !== 'shot') return
  el.dataset.instance = entry.shot.instanceId
  el.dataset.file = entry.shot.fileName
  observer?.observe(el)
}

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue
        const el = e.target as HTMLElement
        if (el.dataset.instance && el.dataset.file) void loadThumb(el.dataset.instance, el.dataset.file)
        observer?.unobserve(el)
      }
    },
    { rootMargin: '200px' },
  )
  void load()
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <section class="picker flex min-h-0 flex-col" :aria-label="t('social.picker.title', { n: selected.length, max: MAX_IMAGES })" data-testid="image-picker">
    <header class="flex items-center gap-2 border-b border-base-800 px-4 py-2.5">
      <h3 class="display flex-1 truncate text-base text-base-50">
        {{ t('social.picker.title', { n: selected.length, max: MAX_IMAGES }) }}
      </h3>
      <input
        v-if="searching"
        v-model="search"
        class="field w-44 py-1 text-xs"
        :placeholder="t('social.picker.search')"
        :aria-label="t('social.picker.search')"
        autofocus
        @keydown.esc="searching = false; search = ''"
      />
      <button class="btn-icon size-8" :aria-label="t('social.picker.search')" :title="t('social.picker.search')" @click="searching = !searching; search = ''">
        <SocialIcon name="search" class="size-4" />
      </button>
      <button class="btn btn-primary px-4 py-1.5" data-testid="picker-done" @click="emit('done')">{{ t('common.actions.done') }}</button>
    </header>

    <div class="flex items-center gap-3 px-4 py-2">
      <div class="flex gap-1 text-sm" role="tablist">
        <button
          v-for="f in filters"
          :key="f"
          class="tab px-3 py-1"
          :class="{ 'tab-on': filter === f }"
          role="tab"
          :aria-selected="filter === f"
          @click="filter = f"
        >
          {{ t(`social.picker.filters.${f}`) }}
        </button>
      </div>
      <button class="btn btn-ghost ml-auto px-3 py-1 text-xs" :disabled="busy" data-testid="picker-upload" @click="pickFiles">
        <SocialIcon name="upload" class="size-3.5" />{{ t('social.picker.upload') }}
      </button>
      <label class="flex items-center gap-2 text-base-400" :title="t('social.picker.size')">
        <SocialIcon name="image" class="size-4" />
        <input v-model.number="columns" type="range" min="2" max="6" step="1" class="w-20 accent-redstone-500" :aria-label="t('social.picker.size')" />
        <SocialIcon name="library" class="size-4" />
      </label>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
      <div v-if="loading" class="grid gap-2" :style="{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }">
        <div v-for="i in columns * 2" :key="i" class="skeleton aspect-video" />
      </div>
      <div v-else-if="!shown.length" class="grid place-items-center py-10 text-center text-sm text-base-400">
        <p>{{ filter === 'favorites' ? t('social.picker.noFavorites') : filter === 'uploads' ? t('social.picker.noUploads') : t('social.picker.noScreenshots') }}</p>
        <button v-if="filter === 'uploads'" class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="pickFiles">{{ t('social.picker.upload') }}</button>
      </div>
      <ul v-else class="grid gap-2" :style="{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }">
        <li v-for="entry in shown" :key="entry.key" :ref="(el) => observe(el, entry)" class="group/tile relative">
          <button
            class="tile block aspect-video w-full overflow-hidden rounded-md bg-base-850"
            :class="{ 'tile-on': indexOf(entry) >= 0 }"
            :aria-pressed="indexOf(entry) >= 0"
            :aria-label="entry.kind === 'local' ? entry.image.name : `${entry.shot.instanceName} · ${entry.shot.fileName}`"
            :title="entry.kind === 'local' ? entry.image.name : `${entry.shot.instanceName} · ${entry.shot.fileName}`"
            @click="toggle(entry)"
          >
            <img v-if="previewOf(entry)" :src="previewOf(entry)" alt="" class="size-full object-cover" loading="lazy" draggable="false" />
          </button>
          <span v-if="indexOf(entry) >= 0" class="pointer-events-none absolute top-1.5 left-1.5 grid size-6 place-items-center rounded-full bg-redstone-500 text-xs font-bold text-white shadow">
            {{ indexOf(entry) + 1 }}
          </span>
          <button
            v-if="entry.kind === 'shot'"
            class="absolute top-1.5 right-1.5 grid size-7 place-items-center rounded-md bg-black/55 transition-opacity"
            :class="favorites.has(shotKey(entry.shot)) ? 'text-lamp-400 opacity-100' : 'text-white opacity-0 group-hover/tile:opacity-100'"
            :aria-label="favorites.has(shotKey(entry.shot)) ? t('social.picker.unfavorite') : t('social.picker.favorite')"
            :title="favorites.has(shotKey(entry.shot)) ? t('social.picker.unfavorite') : t('social.picker.favorite')"
            @click.stop="toggleFavorite(entry.shot)"
          >
            <SocialIcon name="star" class="size-4" :fill="favorites.has(shotKey(entry.shot))" />
          </button>
        </li>
      </ul>
      <p class="mt-3 text-center text-[11px] text-base-400">{{ t('social.picker.dropHint') }}</p>
    </div>
  </section>
</template>

<style scoped>
.picker {
  background: var(--color-base-900);
}
.tile {
  outline: 2px solid transparent;
  outline-offset: -2px;
  transition: outline-color 0.15s, filter 0.15s;
}
.tile:hover {
  filter: brightness(1.08);
}
.tile-on {
  outline-color: var(--color-redstone-500);
}
</style>
