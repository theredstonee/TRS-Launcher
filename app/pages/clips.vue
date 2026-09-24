<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { Clip, ClipUsage } from '~/types'

// Clips & Aufnahmen aller Instanzen: laufende Aufnahme steuern, abspielen,
// umbenennen, im Ordner zeigen, löschen, Speicherplatz im Blick.
// Videos und Vorschaubilder gibt der Kern einzeln frei (Asset-Protokoll).
const toasts = useToasts()
const instances = useInstancesStore()
const settings = useSettingsStore()
const clipsStore = useClipsStore()

const clips = ref<Clip[]>([])
const usage = ref<ClipUsage | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const instanceFilter = ref<string>('all')
const thumbs = ref<Record<string, string>>({})
const playing = ref<{ clip: Clip; src: string } | null>(null)
const toDelete = ref<Clip | null>(null)
const renaming = ref<{ clip: Clip; name: string } | null>(null)
const renameBusy = ref(false)

const enabled = computed(() => settings.current?.clips.enabled ?? false)

const usedInstances = computed(() => {
  const seen = new Map<string, string>()
  for (const c of clips.value) seen.set(c.instanceId, c.instanceName)
  return [...seen].map(([id, name]) => ({ id, name })).sort((a, b) => compareText(a.name, b.name))
})

const visible = computed(() =>
  instanceFilter.value === 'all' ? clips.value : clips.value.filter((c) => c.instanceId === instanceFilter.value),
)

const groups = computed(() => {
  const map = new Map<string, Clip[]>()
  const dayFormat = new Intl.DateTimeFormat(intlLocale(), { dateStyle: 'full' })
  for (const clip of visible.value) {
    const day = clip.createdAt ? dayFormat.format(new Date(clip.createdAt)) : t('clips.noDate')
    map.set(day, [...(map.get(day) ?? []), clip])
  }
  return [...map].map(([day, items]) => ({ day, items }))
})

const share = computed(() => (usage.value ? usageShare(usage.value.usedBytes, usage.value.limitBytes) : 0))

async function load() {
  loading.value = true
  try {
    const [list, u] = await Promise.all([backend.listClips(), backend.clipUsage()])
    clips.value = list
    usage.value = u
    error.value = null
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!instances.items.length) instances.load().catch(() => {})
  if (!settings.current) settings.load().catch(() => {})
  load()
})
// Neuer Clip gespeichert (Taste im Spiel oder Knopf hier) → Liste neu laden.
watch(() => clipsStore.version, () => load())

// --- Laufende Aufnahmen ------------------------------------------------------------

const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null
onMounted(() => (ticker = setInterval(() => (now.value = Date.now()), 500)))
onBeforeUnmount(() => ticker && clearInterval(ticker))

const live = computed(() =>
  clipsStore.active.map((s) => ({
    ...s,
    name: instances.items.find((i) => i.id === s.instanceId)?.name ?? s.instanceId,
    elapsed: s.recording ? formatClipDuration(recordingElapsed(s.recordingMs, s.receivedAt, now.value)) : null,
  })),
)

const bufferSeconds = computed(() => settings.current?.clips.bufferSeconds ?? 30)

// --- Vorschaubilder erst bei Sichtbarkeit ------------------------------------------

let observer: IntersectionObserver | null = null
const pending = new Set<string>()

function observe(el: Element | null, clip: Clip) {
  if (!el || !(el instanceof HTMLElement)) return
  el.dataset.instance = clip.instanceId
  el.dataset.file = clip.fileName
  observer?.observe(el)
}

async function loadThumb(clip: Clip) {
  const id = clipKey(clip)
  if (thumbs.value[id] || pending.has(id)) return
  pending.add(id)
  try {
    const path = await backend.clipThumbnail(clip.instanceId, clip.fileName)
    if (path) thumbs.value = { ...thumbs.value, [id]: convertFileSrc(path) }
  } catch {
    // Ohne Vorschau bleibt die Kachel dunkel.
  } finally {
    pending.delete(id)
  }
}

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const el = entry.target as HTMLElement
        const clip = clips.value.find((c) => c.instanceId === el.dataset.instance && c.fileName === el.dataset.file)
        if (clip) void loadThumb(clip)
        observer?.unobserve(el)
      }
    },
    { rootMargin: '200px' },
  )
})
onBeforeUnmount(() => observer?.disconnect())

// --- Abspielen ----------------------------------------------------------------------

async function play(clip: Clip) {
  try {
    const path = await backend.clipVideo(clip.instanceId, clip.fileName)
    if (path) playing.value = { clip, src: convertFileSrc(path) }
  } catch (e) {
    toasts.error(e)
  }
}

function step(delta: number) {
  const cur = playing.value
  if (!cur || !visible.value.length) return
  const index = visible.value.findIndex((c) => clipKey(c) === clipKey(cur.clip))
  const next = visible.value[(index + delta + visible.value.length) % visible.value.length]
  if (next) void play(next)
}

function onKey(event: KeyboardEvent) {
  if (!playing.value || renaming.value || toDelete.value) return
  if (event.key === 'Escape') playing.value = null
  else if (event.key === 'ArrowRight' && event.altKey) step(1)
  else if (event.key === 'ArrowLeft' && event.altKey) step(-1)
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// --- Aktionen ----------------------------------------------------------------------

function reveal(clip: Clip) {
  backend.revealClip(clip.instanceId, clip.fileName).catch((e) => toasts.error(e))
}

function openFolder() {
  backend.openClipsFolder().catch((e) => toasts.error(e))
}

function startRename(clip: Clip) {
  renaming.value = { clip, name: clipStem(clip.fileName) }
}

const renameValid = computed(() => !!renaming.value && isValidClipName(renaming.value.name))

async function confirmRename() {
  const r = renaming.value
  if (!r || !renameValid.value) return
  renameBusy.value = true
  try {
    const newName = await backend.renameClip(r.clip.instanceId, r.clip.fileName, r.name.trim())
    const oldKey = clipKey(r.clip)
    clips.value = clips.value.map((c) => (clipKey(c) === oldKey ? { ...c, fileName: newName } : c))
    if (playing.value && clipKey(playing.value.clip) === oldKey) playing.value = null
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
    clips.value = clips.value.filter((c) => clipKey(c) !== id)
    if (playing.value && clipKey(playing.value.clip) === id) playing.value = null
    backend.clipUsage().then((u) => (usage.value = u)).catch(() => {})
    toasts.ok(t('clips.toasts.trashed'))
  } catch (e) {
    toasts.error(e)
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col p-6">
    <PageHeader :title="t('clips.title')" :subtitle="t('clips.subtitle', clips.length)">
      <select v-if="usedInstances.length > 1" v-model="instanceFilter" class="field h-9 w-56 py-1" :aria-label="t('clips.instanceLabel')">
        <option value="all">{{ t('clips.allInstances') }}</option>
        <option v-for="i in usedInstances" :key="i.id" :value="i.id">{{ i.name }}</option>
      </select>
      <button class="btn btn-ghost h-9 py-1 text-xs" @click="openFolder">{{ t('common.actions.openFolder') }}</button>
      <button class="btn-icon" :title="t('clips.settings')" :aria-label="t('clips.settings')" @click="settings.open('clips')">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path :d="icons.gear" /></svg>
      </button>
      <button class="btn-icon" :title="t('clips.reload')" :aria-label="t('clips.reload')" @click="load">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5" /></svg>
      </button>
    </PageHeader>

    <!-- Aus: kurz erklären, wo es angeht -->
    <section v-if="settings.current && !enabled" class="card mb-4 flex items-center gap-4 px-4 py-3">
      <svg viewBox="0 0 24 24" class="size-6 shrink-0 text-base-400" fill="none" stroke="currentColor" stroke-width="1.8"><path :d="icons.clips" /></svg>
      <div class="min-w-0 flex-1">
        <p class="text-sm font-medium text-base-50">{{ t('clips.off.title') }}</p>
        <p class="text-xs text-base-400">{{ t('clips.off.text') }}</p>
      </div>
      <button class="btn btn-primary shrink-0" @click="settings.open('clips')">{{ t('clips.off.action') }}</button>
    </section>

    <!-- Laufende Spiele -->
    <section v-if="live.length" class="mb-4 space-y-2" :aria-label="t('clips.live.label')">
      <div v-for="s in live" :key="s.instanceId" class="card flex flex-wrap items-center gap-3 px-4 py-3">
        <span
          class="size-3 shrink-0 rounded-full"
          :class="s.recording ? 'animate-pulse bg-redstone-500' : s.buffer ? 'border-2 border-base-400' : 'bg-base-700'"
          aria-hidden="true"
        />
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium text-base-50">{{ s.name }}</p>
          <p class="text-xs text-base-400">
            <template v-if="s.recording">{{ t('clips.live.recording', { time: s.elapsed ?? '0:00' }) }}</template>
            <template v-else-if="s.buffer">{{ t('clips.live.buffer', { seconds: bufferSeconds }) }}</template>
            <template v-else>{{ reasonText(s.reason) }}</template>
            <span v-if="s.encoder" class="ml-1 text-base-600">· {{ tKey(`clips.encoders.${s.encoder}`) }}</span>
          </p>
        </div>
        <button class="btn btn-ghost py-1.5 text-xs" :disabled="!s.buffer" @click="clipsStore.action(s.instanceId, false)">
          {{ t('clips.live.saveClip') }}
        </button>
        <button
          class="btn py-1.5 text-xs"
          :class="s.recording ? 'btn-danger' : 'btn-ghost'"
          :disabled="!s.buffer"
          @click="clipsStore.action(s.instanceId, true)"
        >
          {{ s.recording ? t('clips.live.stop') : t('clips.live.record') }}
        </button>
      </div>
    </section>

    <!-- Speicherplatz -->
    <div v-if="usage && usage.count" class="mb-4">
      <div class="flex items-center justify-between text-xs text-base-400">
        <span>{{ t('clips.usage', { used: formatBytes(usage.usedBytes), limit: formatBytes(usage.limitBytes) }) }}</span>
        <span v-if="usage.freeBytes !== null">{{ t('clips.free', { free: formatBytes(usage.freeBytes) }) }}</span>
      </div>
      <div class="mt-1 h-1.5 overflow-hidden rounded-full bg-base-800" role="progressbar" :aria-valuenow="Math.round(share)" aria-valuemin="0" aria-valuemax="100">
        <div class="h-full rounded-full" :class="share > 90 ? 'bg-warn' : 'bg-redstone-500'" :style="{ width: `${share}%` }" />
      </div>
    </div>

    <p v-if="error" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ error }}</p>

    <div v-else-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-3">
      <div v-for="i in 6" :key="i" class="skeleton aspect-video" />
    </div>

    <RedstoneEmpty v-else-if="!visible.length" :seed="0x5c" :title="t('clips.empty.title')" :text="t('clips.empty.text')" />

    <div v-else class="min-h-0 flex-1 overflow-y-auto pr-1">
      <section v-for="group in groups" :key="group.day" class="mb-6">
        <h2 class="mb-2 text-xs font-medium text-base-400">{{ group.day }} · {{ group.items.length }}</h2>
        <ul class="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-3">
          <li
            v-for="clip in group.items"
            :key="clipKey(clip)"
            :ref="(el) => observe(el as Element | null, clip)"
            class="group card card-hover overflow-hidden"
          >
            <button class="relative block w-full" :title="t('clips.play', { name: clip.fileName })" @click="play(clip)">
              <img
                v-if="thumbs[clipKey(clip)]"
                :src="thumbs[clipKey(clip)]"
                alt=""
                class="aspect-video w-full object-cover transition-transform duration-200 group-hover:scale-[1.03]"
              />
              <div v-else class="aspect-video w-full bg-base-850" />
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
                <p class="truncate text-[11px] text-base-600">{{ clip.instanceName }} · {{ formatBytes(clip.size) }}</p>
              </div>
              <div class="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
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

    <!-- Abspielen ------------------------------------------------------------------- -->
    <div
      v-if="playing"
      class="fixed inset-0 z-50 flex flex-col bg-black/90 p-4"
      role="dialog"
      aria-modal="true"
      :aria-label="t('clips.player.label')"
      @mousedown.self="playing = null"
    >
      <header class="flex items-center gap-3 px-2 pb-3 text-sm text-base-200">
        <div class="min-w-0">
          <p class="truncate font-medium">{{ clipStem(playing.clip.fileName) }}</p>
          <p class="truncate text-xs text-base-400">
            {{ playing.clip.instanceName }} · {{ formatDate(playing.clip.createdAt) }} · {{ formatClipDuration(playing.clip.durationMs) }}
          </p>
        </div>
        <div class="ml-auto flex shrink-0 items-center gap-1.5">
          <button class="btn btn-ghost py-1.5 text-xs" @click="startRename(playing.clip)">{{ t('clips.rename') }}</button>
          <button class="btn btn-ghost py-1.5 text-xs" @click="reveal(playing.clip)">{{ t('clips.showInFolder') }}</button>
          <button class="btn btn-ghost py-1.5 text-xs hover:text-redstone-300" @click="toDelete = playing.clip">{{ t('common.actions.delete') }}</button>
          <button class="btn-icon" :title="t('common.actions.close')" :aria-label="t('clips.player.close')" @click="playing = null">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
      </header>
      <div class="flex min-h-0 flex-1 items-center justify-center" @mousedown.self="playing = null">
        <video :key="playing.src" :src="playing.src" controls autoplay class="max-h-full max-w-full rounded-lg bg-black" />
      </div>
      <p class="pt-2 text-center text-xs text-base-600">{{ t('clips.player.hint') }}</p>
    </div>

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
