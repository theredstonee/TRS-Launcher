<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { GalleryShot } from '~/types'

// Alle Screenshots aller Instanzen: nach Tag gruppiert, mit Vollbild-Ansicht.
// Vorschaubilder erzeugt der Kern und gibt sie einzeln frei; geladen wird erst,
// wenn eine Kachel sichtbar wird.
const toasts = useToasts()
const instances = useInstancesStore()

const shots = ref<GalleryShot[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const instanceFilter = ref<string>('all')
const thumbs = ref<Record<string, string>>({})
const full = ref<Record<string, string>>({})
const viewerIndex = ref<number | null>(null)
const toDelete = ref<GalleryShot | null>(null)

const key = (shot: GalleryShot) => `${shot.instanceId}/${shot.fileName}`

const usedInstances = computed(() => {
  const seen = new Map<string, string>()
  for (const shot of shots.value) seen.set(shot.instanceId, shot.instanceName)
  return [...seen].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, 'de'))
})

const visible = computed(() =>
  instanceFilter.value === 'all' ? shots.value : shots.value.filter((s) => s.instanceId === instanceFilter.value),
)

/** Nach Aufnahmetag gruppiert – so liest sich die Galerie wie ein Tagebuch. */
const groups = computed(() => {
  const map = new Map<string, GalleryShot[]>()
  for (const shot of visible.value) {
    const day = shot.takenAt ? new Date(shot.takenAt).toLocaleDateString('de', { dateStyle: 'full' }) : 'Ohne Datum'
    map.set(day, [...(map.get(day) ?? []), shot])
  }
  return [...map].map(([day, items]) => ({ day, items }))
})

const current = computed(() => (viewerIndex.value === null ? null : (visible.value[viewerIndex.value] ?? null)))

async function load() {
  loading.value = true
  try {
    shots.value = await backend.allScreenshots()
    error.value = null
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!instances.items.length) instances.load().catch(() => {})
  load()
})

// --- Vorschaubilder erst bei Sichtbarkeit laden ---------------------------------

let observer: IntersectionObserver | null = null
const pending = new Set<string>()

function observe(el: Element | null, shot: GalleryShot) {
  if (!el || !(el instanceof HTMLElement)) return
  el.dataset.instance = shot.instanceId
  el.dataset.file = shot.fileName
  observer?.observe(el)
}

async function loadThumb(shot: GalleryShot) {
  const id = key(shot)
  if (thumbs.value[id] || pending.has(id)) return
  pending.add(id)
  try {
    const path = await backend.screenshotThumbnail(shot.instanceId, shot.fileName)
    if (path) thumbs.value = { ...thumbs.value, [id]: convertFileSrc(path) }
  } catch {
    // Kaputte Datei: Kachel bleibt grau.
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
        const shot = shots.value.find((s) => s.instanceId === el.dataset.instance && s.fileName === el.dataset.file)
        if (shot) void loadThumb(shot)
        observer?.unobserve(el)
      }
    },
    { rootMargin: '200px' },
  )
})
onBeforeUnmount(() => observer?.disconnect())

// --- Vollbild ----------------------------------------------------------------------

async function openViewer(shot: GalleryShot) {
  viewerIndex.value = visible.value.findIndex((s) => key(s) === key(shot))
  await loadFull(shot)
}

async function loadFull(shot: GalleryShot) {
  const id = key(shot)
  if (full.value[id]) return
  try {
    const path = await backend.screenshotImage(shot.instanceId, shot.fileName)
    if (path) full.value = { ...full.value, [id]: convertFileSrc(path) }
  } catch (e) {
    toasts.error(e)
  }
}

async function step(delta: number) {
  if (viewerIndex.value === null || !visible.value.length) return
  const next = (viewerIndex.value + delta + visible.value.length) % visible.value.length
  viewerIndex.value = next
  const shot = visible.value[next]
  if (shot) await loadFull(shot)
}

function onKey(event: KeyboardEvent) {
  if (viewerIndex.value === null) return
  if (event.key === 'Escape') viewerIndex.value = null
  else if (event.key === 'ArrowRight') void step(1)
  else if (event.key === 'ArrowLeft') void step(-1)
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// --- Aktionen ------------------------------------------------------------------------

async function copy(shot: GalleryShot) {
  try {
    await backend.copyScreenshot(shot.instanceId, shot.fileName)
    toasts.ok('Bild in die Zwischenablage kopiert')
  } catch (e) {
    toasts.error(e)
  }
}

function reveal(shot: GalleryShot) {
  backend.revealScreenshot(shot.instanceId, shot.fileName).catch((e) => toasts.error(e))
}

async function confirmDelete() {
  const shot = toDelete.value
  toDelete.value = null
  if (!shot) return
  try {
    await backend.trashScreenshot(shot.instanceId, shot.fileName)
    const id = key(shot)
    shots.value = shots.value.filter((s) => key(s) !== id)
    if (viewerIndex.value !== null) {
      viewerIndex.value = visible.value.length ? Math.min(viewerIndex.value, visible.value.length - 1) : null
    }
    toasts.ok('In den Papierkorb gelegt')
  } catch (e) {
    toasts.error(e)
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col p-6">
    <PageHeader title="Screenshots" :subtitle="`${shots.length} Bilder aus allen Instanzen`">
      <select v-if="usedInstances.length > 1" v-model="instanceFilter" class="field h-9 w-56 py-1" aria-label="Instanz">
        <option value="all">Alle Instanzen</option>
        <option v-for="i in usedInstances" :key="i.id" :value="i.id">{{ i.name }}</option>
      </select>
      <button class="btn-icon" title="Neu laden" aria-label="Neu laden" @click="load">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5" /></svg>
      </button>
    </PageHeader>

    <p v-if="error" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ error }}</p>

    <div v-else-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3">
      <div v-for="i in 8" :key="i" class="skeleton aspect-video" />
    </div>

    <div v-else-if="!visible.length" class="card px-6 py-12 text-center">
      <h2 class="font-semibold">Noch keine Screenshots</h2>
      <p class="mx-auto mt-1 max-w-md text-sm text-base-400">
        Drück im Spiel F2 – die Bilder aller Instanzen sammeln sich hier.
      </p>
    </div>

    <div v-else class="min-h-0 flex-1 overflow-y-auto pr-1">
      <section v-for="group in groups" :key="group.day" class="mb-6">
        <h2 class="mb-2 text-xs font-medium text-base-400">{{ group.day }} · {{ group.items.length }}</h2>
        <ul class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3">
          <li
            v-for="shot in group.items"
            :key="key(shot)"
            :ref="(el) => observe(el as Element | null, shot)"
            class="group card card-hover overflow-hidden"
          >
            <button class="block w-full" :title="`${shot.fileName} öffnen`" @click="openViewer(shot)">
              <img
                v-if="thumbs[key(shot)]"
                :src="thumbs[key(shot)]"
                alt=""
                class="aspect-video w-full object-cover transition-transform duration-200 group-hover:scale-[1.03]"
              />
              <div v-else class="aspect-video w-full bg-base-850" />
            </button>
            <div class="flex items-center justify-between gap-2 px-2.5 py-1.5 text-xs">
              <div class="min-w-0">
                <p class="truncate text-base-200">{{ shot.instanceName }}</p>
                <p class="truncate text-[11px] text-base-600">{{ formatDate(shot.takenAt) }} · {{ formatBytes(shot.size) }}</p>
              </div>
              <div class="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                <button class="btn-icon size-7" title="In die Zwischenablage kopieren" aria-label="Kopieren" @click="copy(shot)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="1.5" /><path d="M5 15V5a1 1 0 0 1 1-1h9" /></svg>
                </button>
                <button class="btn-icon size-7" title="Im Ordner zeigen" aria-label="Im Ordner zeigen" @click="reveal(shot)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /></svg>
                </button>
                <button class="btn-icon size-7 hover:text-redstone-300" title="Löschen" aria-label="Löschen" @click="toDelete = shot">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
                </button>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <!-- Vollbild ------------------------------------------------------------------ -->
    <div
      v-if="current"
      class="fixed inset-0 z-50 flex flex-col bg-black/90 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Screenshot"
      @mousedown.self="viewerIndex = null"
    >
      <header class="flex items-center gap-3 px-2 pb-3 text-sm text-base-200">
        <div class="min-w-0">
          <p class="truncate font-medium">{{ current.fileName }}</p>
          <p class="truncate text-xs text-base-400">{{ current.instanceName }} · {{ formatDate(current.takenAt) }}</p>
        </div>
        <div class="ml-auto flex shrink-0 items-center gap-1.5">
          <button class="btn btn-ghost py-1.5 text-xs" @click="copy(current)">Kopieren</button>
          <button class="btn btn-ghost py-1.5 text-xs" @click="reveal(current)">Im Ordner zeigen</button>
          <button class="btn btn-ghost py-1.5 text-xs hover:text-redstone-300" @click="toDelete = current">Löschen</button>
          <button class="btn-icon" title="Schließen" aria-label="Vollbild schließen" @click="viewerIndex = null">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
      </header>

      <div class="relative flex min-h-0 flex-1 items-center justify-center" @mousedown.self="viewerIndex = null">
        <button v-if="visible.length > 1" class="btn-icon absolute left-2 size-11" title="Vorheriges" aria-label="Vorheriges Bild" @click="step(-1)">
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M15 5l-7 7 7 7" /></svg>
        </button>
        <img v-if="full[key(current)]" :src="full[key(current)]" :alt="current.fileName" class="max-h-full max-w-full rounded-lg object-contain" />
        <div v-else class="skeleton h-3/4 w-3/4" />
        <button v-if="visible.length > 1" class="btn-icon absolute right-2 size-11" title="Nächstes" aria-label="Nächstes Bild" @click="step(1)">
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M9 5l7 7-7 7" /></svg>
        </button>
      </div>
      <p class="pt-2 text-center text-xs text-base-600">
        Pfeiltasten zum Blättern · Esc schließt · „Kopieren“ legt das Bild zum Einfügen in die Zwischenablage
      </p>
    </div>

    <BaseDialog v-if="toDelete" title="Screenshot löschen?" @close="toDelete = null">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ toDelete.fileName }}</strong> wandert in den Papierkorb von Windows – von dort
        lässt er sich zurückholen.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDelete">Löschen</button>
      </template>
    </BaseDialog>
  </div>
</template>
