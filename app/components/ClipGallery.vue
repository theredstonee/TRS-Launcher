<script setup lang="ts">
import type { Clip, ClipStrip } from '~/types'

// Clip-Galerie: Kacheln mit Vorschaubild, Dauer und Datum; Suche, Instanz-Filter,
// Sortierung; Klick öffnet den Player. Überall gleich – auf der Clip-Seite (alle
// Instanzen) und im Reiter „Clips“ einer Instanz (`instanceId`).
const props = defineProps<{
  /** Nur Clips dieser Instanz (Instanz-Seite). */
  instanceId?: string
  /** Diesen Clip öffnen (Deep-Link, „Im Launcher öffnen“ aus dem Spiel); `nonce` erzwingt erneutes Öffnen. */
  open?: { instanceId: string; fileName: string; nonce: number } | null
}>()
const emit = defineEmits<{ changed: []; loaded: [count: number] }>()

const toasts = useToasts()
const clipsStore = useClipsStore()

const clips = ref<Clip[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const query = ref('')
const instanceFilter = ref<string>('all')
const sort = ref<ClipSort>('newest')
const failedPosters = ref(new Set<string>())

const SORT_KEY = 'trs.clips.sort'
try {
  const stored = localStorage.getItem(SORT_KEY)
  if (stored && (CLIP_SORTS as readonly string[]).includes(stored)) sort.value = stored as ClipSort
} catch {
  // Kein Speicher – egal.
}
watch(sort, (s) => {
  try {
    localStorage.setItem(SORT_KEY, s)
  } catch {
    // Kein Speicher – egal.
  }
})

const usedInstances = computed(() => {
  const seen = new Map<string, string>()
  for (const c of clips.value) seen.set(c.instanceId, c.instanceName)
  return [...seen].map(([id, name]) => ({ id, name })).sort((a, b) => compareText(a.name, b.name))
})

const visible = computed(() =>
  filterClips(clips.value, {
    instanceId: props.instanceId ?? (instanceFilter.value === 'all' ? null : instanceFilter.value),
    query: query.value,
    sort: sort.value,
  }),
)

// Nach Tag gruppieren, solange nach Datum sortiert wird.
const groups = computed(() => {
  if (sort.value !== 'newest' && sort.value !== 'oldest') return [{ day: null as string | null, items: visible.value }]
  const map = new Map<string, Clip[]>()
  const dayFormat = new Intl.DateTimeFormat(intlLocale(), { dateStyle: 'full' })
  for (const clip of visible.value) {
    const day = clip.createdAt ? dayFormat.format(new Date(clip.createdAt)) : t('clips.noDate')
    map.set(day, [...(map.get(day) ?? []), clip])
  }
  return [...map].map(([day, items]) => ({ day: day as string | null, items }))
})

const timeFormat = computed(() => new Intl.DateTimeFormat(intlLocale(), { timeStyle: 'short' }))
function tileDate(clip: Clip): string {
  if (!clip.createdAt) return t('clips.noDate')
  const date = new Date(clip.createdAt)
  return sort.value === 'newest' || sort.value === 'oldest' ? timeFormat.value.format(date) : formatDate(clip.createdAt)
}

async function load() {
  loading.value = true
  try {
    const all = await backend.listClips()
    clips.value = props.instanceId ? all.filter((c) => c.instanceId === props.instanceId) : all
    error.value = null
    emit('loaded', clips.value.length)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
  openRequested()
}
onMounted(load)
// Neuer Clip gespeichert (Taste im Spiel, Knopf, Zuschneiden) → neu laden.
watch(() => clipsStore.version, () => load())
defineExpose({ reload: load })

// --- Player ---------------------------------------------------------------------------
const playing = ref<Clip | null>(null)
const startTrim = ref(false)

function play(clip: Clip, trim = false) {
  startTrim.value = trim
  playing.value = clip
}

function openRequested() {
  const want = props.open
  if (!want || loading.value) return
  const clip = clips.value.find((c) => c.instanceId === want.instanceId && c.fileName === want.fileName)
  if (clip) play(clip)
  else toasts.error(t('errors.clips.gone'))
}
watch(() => props.open?.nonce, () => openRequested())

function onCreated(clip: Clip) {
  clips.value = [clip, ...clips.value.filter((c) => clipKey(c) !== clipKey(clip))]
  emit('changed')
}

// --- Vorschau-Animation beim Überfahren einer Kachel --------------------------------
const hoverKey = ref<string | null>(null)
const hoverStrip = ref<ClipStrip | null>(null)
const hoverFrame = ref(0)
const strips = new Map<string, ClipStrip | null>()
let hoverTimer: ReturnType<typeof setTimeout> | null = null
let frameTimer: ReturnType<typeof setInterval> | null = null

function stopHover() {
  if (hoverTimer) clearTimeout(hoverTimer)
  if (frameTimer) clearInterval(frameTimer)
  hoverTimer = frameTimer = null
  hoverKey.value = null
  hoverStrip.value = null
}
function startHover(clip: Clip) {
  stopHover()
  const key = clipKey(clip)
  hoverKey.value = key
  // Erst nach kurzem Verweilen – sonst erzeugt schnelles Drüberfahren lauter Leisten.
  hoverTimer = setTimeout(async () => {
    let strip = strips.get(key)
    if (strip === undefined) {
      strip = await backend.clipStrip(clip.instanceId, clip.fileName).catch(() => null)
      strips.set(key, strip)
    }
    if (hoverKey.value !== key || !strip) return
    hoverStrip.value = strip
    hoverFrame.value = 0
    frameTimer = setInterval(() => (hoverFrame.value = (hoverFrame.value + 1) % strip.frames), 180)
  }, 450)
}
onBeforeUnmount(stopHover)

function hoverStyle(clip: Clip) {
  const strip = hoverStrip.value
  if (!strip || hoverKey.value !== clipKey(clip)) return null
  const { x, y } = stripFrameAt(strip, hoverFrame.value * strip.intervalMs)
  return {
    backgroundImage: `url("${clipUrl('s', clip)}")`,
    backgroundSize: `${strip.cols * 100}% ${strip.rows * 100}%`,
    backgroundPosition: `${strip.cols > 1 ? (x / strip.frameWidth / (strip.cols - 1)) * 100 : 0}% ${strip.rows > 1 ? (y / strip.frameHeight / (strip.rows - 1)) * 100 : 0}%`,
  }
}

// --- Umbenennen / Löschen -------------------------------------------------------------
const toDelete = ref<Clip | null>(null)
const renaming = ref<{ clip: Clip; name: string } | null>(null)
const renameBusy = ref(false)
const renameValid = computed(() => !!renaming.value && isValidClipName(renaming.value.name))

function startRename(clip: Clip) {
  renaming.value = { clip, name: clipStem(clip.fileName) }
}

async function confirmRename() {
  const r = renaming.value
  if (!r || !renameValid.value) return
  renameBusy.value = true
  try {
    const newName = await backend.renameClip(r.clip.instanceId, r.clip.fileName, r.name.trim())
    const oldKey = clipKey(r.clip)
    const renamed = { ...r.clip, fileName: newName }
    clips.value = clips.value.map((c) => (clipKey(c) === oldKey ? renamed : c))
    if (playing.value && clipKey(playing.value) === oldKey) playing.value = renamed
    renaming.value = null
    toasts.ok(t('clips.toasts.renamed'))
  } catch (e) {
    toasts.error(e)
  } finally {
    renameBusy.value = false
  }
}

async function confirmDelete() {
  const clip = toDelete.value
  toDelete.value = null
  if (!clip) return
  try {
    await backend.trashClip(clip.instanceId, clip.fileName)
    const id = clipKey(clip)
    if (playing.value && clipKey(playing.value) === id) {
      const next = neighbourClip(visible.value, clip, 1)
      playing.value = next && clipKey(next) !== id ? next : null
    }
    clips.value = clips.value.filter((c) => clipKey(c) !== id)
    emit('changed')
    toasts.ok(t('clips.toasts.trashed'))
  } catch (e) {
    toasts.error(e)
  }
}

function reveal(clip: Clip) {
  backend.revealClip(clip.instanceId, clip.fileName).catch((e) => toasts.error(e))
}
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col">
    <!-- Werkzeugleiste -->
    <div v-if="clips.length" class="mb-3 flex flex-wrap items-center gap-2">
      <div class="relative min-w-48 flex-1">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-base-500" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path :d="icons.search" /></svg>
        <input v-model="query" type="search" class="field h-9 w-full py-1 pl-8" :placeholder="t('clips.gallery.search')" :aria-label="t('clips.gallery.search')" />
      </div>
      <select v-if="!instanceId && usedInstances.length > 1" v-model="instanceFilter" class="field h-9 w-52 py-1" :aria-label="t('clips.instanceLabel')">
        <option value="all">{{ t('clips.allInstances') }}</option>
        <option v-for="i in usedInstances" :key="i.id" :value="i.id">{{ i.name }}</option>
      </select>
      <select v-model="sort" class="field h-9 w-44 py-1" :aria-label="t('clips.gallery.sortLabel')">
        <option v-for="s in CLIP_SORTS" :key="s" :value="s">{{ tKey(`clips.gallery.sort.${s}`) }}</option>
      </select>
    </div>

    <p v-if="error" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ error }}</p>

    <div v-else-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-3">
      <div v-for="i in 6" :key="i" class="skeleton aspect-video" />
    </div>

    <RedstoneEmpty v-else-if="!clips.length" :seed="0x5c" :title="t('clips.empty.title')" :text="t('clips.empty.text')" />

    <p v-else-if="!visible.length" class="py-10 text-center text-sm text-base-400">{{ t('clips.gallery.noMatches') }}</p>

    <div v-else class="min-h-0 flex-1 overflow-y-auto pr-1">
      <section v-for="group in groups" :key="group.day ?? 'all'" class="mb-6">
        <h2 v-if="group.day" class="mb-2 text-xs font-medium text-base-400">{{ group.day }} · {{ group.items.length }}</h2>
        <ul class="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-3">
          <li
            v-for="clip in group.items"
            :key="clipKey(clip)"
            class="group card card-hover overflow-hidden"
            @mouseenter="startHover(clip)"
            @mouseleave="stopHover"
          >
            <button class="relative block w-full" :title="t('clips.play', { name: clip.fileName })" @click="play(clip)">
              <img
                v-if="!failedPosters.has(clipKey(clip))"
                :src="clipUrl('p', clip)"
                alt=""
                loading="lazy"
                decoding="async"
                class="aspect-video w-full bg-base-850 object-cover"
                @error="failedPosters.add(clipKey(clip))"
              />
              <div v-else class="grid aspect-video w-full place-items-center bg-base-850 text-base-600">
                <svg viewBox="0 0 24 24" class="size-8" fill="none" stroke="currentColor" stroke-width="1.5"><path :d="icons.clips" /></svg>
              </div>
              <div v-if="hoverStyle(clip)" class="absolute inset-0 bg-no-repeat" :style="hoverStyle(clip)!" aria-hidden="true" />
              <span class="absolute inset-0 grid place-items-center opacity-0 transition-opacity group-hover:opacity-100">
                <span class="grid size-11 place-items-center rounded-full bg-black/60 text-white">
                  <svg viewBox="0 0 24 24" class="size-5" fill="currentColor"><path :d="icons.play" /></svg>
                </span>
              </span>
              <span class="absolute right-1.5 bottom-1.5 rounded bg-black/70 px-1.5 py-0.5 font-mono text-[11px] text-white">
                {{ formatClipDuration(clip.durationMs) }}
              </span>
            </button>
            <div class="flex items-center justify-between gap-2 px-2.5 py-1.5 text-xs">
              <div class="min-w-0">
                <p class="truncate text-base-200" :title="clip.fileName">{{ clipStem(clip.fileName) }}</p>
                <p class="truncate text-[11px] text-base-600">
                  <template v-if="!instanceId">{{ clip.instanceName }} · </template>{{ tileDate(clip) }} · {{ formatBytes(clip.size) }}
                </p>
              </div>
              <div class="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                <button class="btn-icon size-7" :title="t('clips.trim.button')" :aria-label="t('clips.trim.button')" @click="play(clip, true)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM20 4 8.1 15.9M14.5 14.5 20 20M8.1 8.1 12 12" /></svg>
                </button>
                <button class="btn-icon size-7" :title="t('clips.rename')" :aria-label="t('clips.rename')" @click="startRename(clip)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4" /></svg>
                </button>
                <button class="btn-icon size-7" :title="t('clips.showInFolder')" :aria-label="t('clips.showInFolder')" @click="reveal(clip)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /></svg>
                </button>
                <button class="btn-icon size-7 hover:text-redstone-300" :title="t('common.actions.delete')" :aria-label="t('common.actions.delete')" @click="toDelete = clip">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
                </button>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <ClipPlayer
      v-if="playing"
      :key="clipKey(playing)"
      :clip="playing"
      :list="visible"
      :start-trim="startTrim"
      :keys-paused="!!renaming || !!toDelete"
      @close="playing = null"
      @select="(c) => play(c)"
      @rename="startRename"
      @delete="(c) => (toDelete = c)"
      @created="onCreated"
    />

    <BaseDialog v-if="renaming" :title="t('clips.renameDialog.title')" @close="renaming = null">
      <label class="label" for="clip-name">{{ t('clips.renameDialog.label') }}</label>
      <div class="flex items-center gap-2">
        <input id="clip-name" v-model="renaming.name" class="field flex-1" maxlength="170" autocomplete="off" @keydown.enter="confirmRename" />
        <span class="text-xs text-base-400">.mp4</span>
      </div>
      <p v-if="!renameValid" class="mt-1 text-xs text-redstone-300">{{ t('clips.renameDialog.invalid') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="renaming = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!renameValid || renameBusy" @click="confirmRename">{{ t('clips.rename') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="toDelete" :title="t('clips.deleteDialog.title')" @close="toDelete = null">
      <i18n-t keypath="clips.deleteDialog.text" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ toDelete.fileName }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
