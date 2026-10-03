<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { GalleryShot } from '~/types'
import { SHARE_DAYS, ShareQueue, shareGate } from '~/utils/share'

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
const trs = useTrsStore()
const accounts = useAccountsStore()
const sharesOpen = ref(false)
const sharing = ref<Record<string, boolean>>({})
const shareQueue = new ShareQueue()

const key = (shot: GalleryShot) => `${shot.instanceId}/${shot.fileName}`

const usedInstances = computed(() => {
  const seen = new Map<string, string>()
  for (const shot of shots.value) seen.set(shot.instanceId, shot.instanceName)
  return [...seen].map(([id, name]) => ({ id, name })).sort((a, b) => compareText(a.name, b.name))
})

const visible = computed(() =>
  instanceFilter.value === 'all' ? shots.value : shots.value.filter((s) => s.instanceId === instanceFilter.value),
)

/** Nach Aufnahmetag gruppiert – so liest sich die Galerie wie ein Tagebuch. */
const groups = computed(() => {
  const map = new Map<string, GalleryShot[]>()
  const dayFormat = new Intl.DateTimeFormat(intlLocale(), { dateStyle: 'full' })
  for (const shot of visible.value) {
    const day = shot.takenAt ? dayFormat.format(new Date(shot.takenAt)) : t('screenshots.noDate')
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

// --- Handy ------------------------------------------------------------------------------
// Aktionen per ⋮ oder langem Druck als Sheet, im Vollbild wischen zum Blättern, Zurück-Taste schließt.
// Kopieren (Zwischenablage für Bilder) und „Im Ordner zeigen“ gibt es dort nicht.
const mobile = mobileUi
const actionsFor = ref<GalleryShot | null>(null)
let pressTimer: ReturnType<typeof setTimeout> | null = null
let pressStart: { x: number; y: number } | null = null
let pressFired = false
function pressDown(shot: GalleryShot, e: PointerEvent) {
  if (!mobile.value || e.pointerType === 'mouse') return
  pressFired = false
  pressStart = { x: e.clientX, y: e.clientY }
  pressTimer = setTimeout(() => {
    pressFired = true
    pressTimer = null
    actionsFor.value = shot
  }, LONG_PRESS_MS)
}
function pressMove(e: PointerEvent) {
  if (pressStart && longPressCancelled(e.clientX - pressStart.x, e.clientY - pressStart.y)) pressEnd()
}
function pressEnd() {
  if (pressTimer) clearTimeout(pressTimer)
  pressTimer = null
  pressStart = null
}
/** Nach langem Druck keine nachgeahmten Maus-Ereignisse (System-Menü, Schließen des Sheets). */
function pressTouchEnd(e: TouchEvent) {
  if (pressFired && e.cancelable) e.preventDefault()
}
function pressContextMenu(e: MouseEvent) {
  if (mobile.value && (pressTimer || pressFired)) e.preventDefault()
}
function tapShot(shot: GalleryShot) {
  if (pressFired) {
    pressFired = false
    return
  }
  void openViewer(shot)
}
let swipeX: number | null = null
function swipeStart(e: TouchEvent) {
  swipeX = e.touches.length === 1 ? e.touches[0]!.clientX : null
}
function swipeEnd(e: TouchEvent) {
  if (swipeX === null) return
  const dx = (e.changedTouches[0]?.clientX ?? swipeX) - swipeX
  swipeX = null
  if (Math.abs(dx) > 50) void step(dx < 0 ? 1 : -1)
}
let viewerOverlay: number | null = null
watch(
  () => viewerIndex.value !== null,
  (open) => {
    if (open && viewerOverlay === null) viewerOverlay = pushOverlay(() => (viewerIndex.value = null))
    else if (!open && viewerOverlay !== null) {
      removeOverlay(viewerOverlay)
      viewerOverlay = null
    }
  },
)
onBeforeUnmount(() => {
  if (viewerOverlay !== null) removeOverlay(viewerOverlay)
})

// --- Aktionen ------------------------------------------------------------------------

async function copy(shot: GalleryShot) {
  try {
    await backend.copyScreenshot(shot.instanceId, shot.fileName)
    toasts.ok(t('screenshots.toasts.copied'))
  } catch (e) {
    toasts.error(e)
  }
}

function reveal(shot: GalleryShot) {
  backend.revealScreenshot(shot.instanceId, shot.fileName).catch((e) => toasts.error(e))
}

// --- Als Link teilen (API §23) ----------------------------------------------------------

const gate = computed(() =>
  shareGate({ statusLoaded: !!trs.status, enabled: trs.enabled, hasAccount: !!accounts.active, banned: trs.problem === 'banned' }),
)

/** Ohne Einwilligung/Konto: Hinweis statt Anfrage. `true` = Teilen geht. */
function shareAllowed(): boolean {
  switch (gate.value) {
    case 'ok':
      return true
    case 'consent':
      toasts.info(t('shareLink.needConsent'), { label: t('shareLink.turnOn'), run: () => trs.askConsent() })
      return false
    case 'account':
      toasts.info(t('shareLink.needAccount'))
      return false
    case 'banned':
      toasts.info(t('trsGate.banned.title'))
      return false
    default:
      return false
  }
}

async function share(shot: GalleryShot) {
  if (!shareAllowed()) return
  const id = key(shot)
  sharing.value = { ...sharing.value, [id]: true }
  try {
    const result = await shareQueue.run(id, () => backend.social.shareScreenshot(shot.instanceId, shot.fileName))
    if (!result) return
    const open = { label: t('shareLink.open'), run: () => void backend.openExternalUrl(result.url).catch((e) => toasts.error(e)) }
    try {
      await navigator.clipboard.writeText(result.url)
      toasts.ok(t('shareLink.toasts.copied', { days: SHARE_DAYS }), open)
    } catch {
      toasts.info(t('shareLink.toasts.ready'), { label: t('shareLink.myShares'), run: () => (sharesOpen.value = true) })
    }
  } catch (e) {
    toasts.error(e)
  } finally {
    const next = { ...sharing.value }
    delete next[id]
    sharing.value = next
  }
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
    toasts.ok(t('screenshots.toasts.trashed'))
  } catch (e) {
    toasts.error(e)
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col p-6 mobile:h-auto mobile:min-h-full mobile:p-4">
    <PullToRefresh :refresh="load" />
    <PageHeader :title="t('screenshots.title')" :subtitle="t('screenshots.subtitle', shots.length)">
      <select v-if="usedInstances.length > 1" v-model="instanceFilter" class="field h-9 w-56 py-1 mobile:w-48" :aria-label="t('screenshots.instanceLabel')">
        <option value="all">{{ t('screenshots.allInstances') }}</option>
        <option v-for="i in usedInstances" :key="i.id" :value="i.id">{{ i.name }}</option>
      </select>
      <button class="btn btn-ghost h-9 py-1 text-xs" data-testid="shares-open" @click="sharesOpen = true">
        <SocialIcon name="link" class="size-4" />{{ t('shareLink.myShares') }}
      </button>
      <button v-if="!mobile" class="btn-icon" :title="t('screenshots.reload')" :aria-label="t('screenshots.reload')" @click="load">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5" /></svg>
      </button>
    </PageHeader>

    <p v-if="error" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ error }}</p>

    <div v-else-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3 mobile:grid-cols-2 mobile:gap-2">
      <div v-for="i in 8" :key="i" class="skeleton aspect-video" />
    </div>

    <RedstoneEmpty
      v-else-if="!visible.length"
      :seed="0x44"
      :title="t('screenshots.empty.title')"
      :text="t('screenshots.empty.text')"
    />

    <div v-else class="min-h-0 flex-1 overflow-y-auto pr-1 mobile:overflow-visible mobile:pr-0">
      <section v-for="group in groups" :key="group.day" class="mb-6">
        <h2 class="mb-2 text-xs font-medium text-base-400">{{ group.day }} · {{ group.items.length }}</h2>
        <ul class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3 mobile:grid-cols-2 mobile:gap-2">
          <li
            v-for="shot in group.items"
            :key="key(shot)"
            :ref="(el) => observe(el as Element | null, shot)"
            class="group card card-hover overflow-hidden"
            @pointerdown="pressDown(shot, $event)"
            @pointermove="pressMove"
            @pointerup="pressEnd"
            @pointercancel="pressEnd"
            @touchend="pressTouchEnd"
            @contextmenu="pressContextMenu"
          >
            <button class="block w-full" :title="t('screenshots.open', { name: shot.fileName })" @click="tapShot(shot)">
              <img
                v-if="thumbs[key(shot)]"
                :src="thumbs[key(shot)]"
                alt=""
                class="aspect-video w-full object-cover transition-transform duration-200 group-hover:scale-[1.03]"
              />
              <div v-else class="aspect-video w-full bg-base-850" />
            </button>
            <div class="flex items-center justify-between gap-2 px-2.5 py-1.5 text-xs mobile:gap-1 mobile:py-1 mobile:pr-0">
              <div class="min-w-0">
                <p class="truncate text-base-200">{{ shot.instanceName }}</p>
                <p class="truncate text-[11px] text-base-600">{{ formatDate(shot.takenAt) }} · {{ formatBytes(shot.size) }}</p>
              </div>
              <button
                v-if="mobile"
                class="grid size-10 shrink-0 place-items-center text-base-400"
                :aria-label="t('content.row.moreActions', { name: shot.fileName })"
                @click="actionsFor = shot"
              >
                <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="12" cy="5.5" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="12" cy="18.5" r="1.7" /></svg>
              </button>
              <div v-else class="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                <button class="btn-icon size-7" :title="t('screenshots.copyTitle')" :aria-label="t('common.actions.copy')" @click="copy(shot)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="1.5" /><path d="M5 15V5a1 1 0 0 1 1-1h9" /></svg>
                </button>
                <button
                  class="btn-icon size-7"
                  :class="{ 'animate-pulse text-lamp-300': sharing[key(shot)] }"
                  :title="t('shareLink.shareTitle')"
                  :aria-label="t('shareLink.share')"
                  :disabled="sharing[key(shot)]"
                  data-testid="share-shot"
                  @click="share(shot)"
                >
                  <SocialIcon name="link" class="size-3.5" />
                </button>
                <button class="btn-icon size-7" :title="t('screenshots.showInFolder')" :aria-label="t('screenshots.showInFolder')" @click="reveal(shot)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /></svg>
                </button>
                <button class="btn-icon size-7 hover:text-redstone-300" :title="t('common.actions.delete')" :aria-label="t('common.actions.delete')" @click="toDelete = shot">
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
      class="fixed inset-0 z-50 flex flex-col bg-black/90 p-4 mobile:px-2 mobile:pt-[calc(var(--safe-top)+0.5rem)] mobile:pb-[calc(var(--safe-bottom)+0.5rem)]"
      role="dialog"
      aria-modal="true"
      :aria-label="t('screenshots.viewer.label')"
      @mousedown.self="viewerIndex = null"
    >
      <header class="flex items-center gap-3 px-2 pb-3 text-sm text-base-200">
        <div class="min-w-0">
          <p class="truncate font-medium">{{ current.fileName }}</p>
          <p class="truncate text-xs text-base-400">{{ current.instanceName }} · {{ formatDate(current.takenAt) }}</p>
        </div>
        <div class="ml-auto flex shrink-0 items-center gap-1.5">
          <template v-if="!mobile">
          <button class="btn btn-ghost py-1.5 text-xs" @click="copy(current)">{{ t('common.actions.copy') }}</button>
          <button class="btn btn-ghost py-1.5 text-xs" :disabled="sharing[key(current)]" :title="t('shareLink.shareTitle')" data-testid="share-current" @click="share(current)">
            <SocialIcon name="link" class="size-3.5" />{{ sharing[key(current)] ? t('shareLink.sharing') : t('shareLink.share') }}
          </button>
          <button class="btn btn-ghost py-1.5 text-xs" @click="reveal(current)">{{ t('screenshots.showInFolder') }}</button>
          <button class="btn btn-ghost py-1.5 text-xs hover:text-redstone-300" @click="toDelete = current">{{ t('common.actions.delete') }}</button>
          </template>
          <button class="btn-icon" :title="t('common.actions.close')" :aria-label="t('screenshots.viewer.closeLabel')" @click="viewerIndex = null">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
      </header>

      <div class="relative flex min-h-0 flex-1 items-center justify-center" @mousedown.self="viewerIndex = null" @touchstart.passive="swipeStart" @touchend="swipeEnd">
        <button v-if="visible.length > 1 && !mobile" class="btn-icon absolute left-2 size-11" :title="t('screenshots.viewer.prev')" :aria-label="t('screenshots.viewer.prevLabel')" @click="step(-1)">
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M15 5l-7 7 7 7" /></svg>
        </button>
        <img v-if="full[key(current)]" :src="full[key(current)]" :alt="current.fileName" class="max-h-full max-w-full rounded-lg object-contain" />
        <div v-else class="skeleton h-3/4 w-3/4" />
        <button v-if="visible.length > 1 && !mobile" class="btn-icon absolute right-2 size-11" :title="t('screenshots.viewer.next')" :aria-label="t('screenshots.viewer.nextLabel')" @click="step(1)">
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M9 5l7 7-7 7" /></svg>
        </button>
      </div>
      <p v-if="!mobile" class="pt-2 text-center text-xs text-base-600">
        {{ t('screenshots.viewer.hint') }}
      </p>
      <!-- Handy: Aktionen unten, gut mit dem Daumen erreichbar. -->
      <div v-else class="flex justify-center gap-2 pt-3">
        <button class="btn btn-ghost flex-1" :disabled="sharing[key(current)]" @click="share(current)">
          <SocialIcon name="link" class="size-4" />{{ sharing[key(current)] ? t('shareLink.sharing') : t('shareLink.share') }}
        </button>
        <button class="btn btn-ghost flex-1 text-redstone-300" @click="toDelete = current">{{ t('common.actions.delete') }}</button>
      </div>
    </div>

    <MobileSheet v-if="actionsFor" :title="actionsFor.fileName" @close="actionsFor = null">
      <div class="space-y-0.5">
        <button class="menu-item" @click="openViewer(actionsFor); actionsFor = null">{{ t('common.actions.open') }}</button>
        <button class="menu-item" :disabled="sharing[key(actionsFor)]" @click="share(actionsFor); actionsFor = null">{{ t('shareLink.share') }}</button>
        <div class="my-1 border-t border-base-700" />
        <button class="menu-item text-redstone-300" @click="toDelete = actionsFor; actionsFor = null">{{ t('common.actions.delete') }}</button>
      </div>
    </MobileSheet>

    <SharedImagesDialog v-if="sharesOpen" @close="sharesOpen = false" @deleted="(id) => shareQueue.forget(id)" />

    <BaseDialog v-if="toDelete" :title="t('screenshots.deleteDialog.title')" @close="toDelete = null">
      <i18n-t
        :keypath="isLinux ? 'screenshots.deleteDialog.textLinux' : 'screenshots.deleteDialog.text'"
        tag="p"
        scope="global"
        class="text-sm text-base-200"
      >
        <template #name><strong class="text-base-50">{{ toDelete.fileName }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
