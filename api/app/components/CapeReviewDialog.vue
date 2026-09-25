<script setup lang="ts">
import type { Messages } from '~/utils/messages'

// Prüf-Dialog für hochgeladene Umhänge: 3D-Puppe, pixelgenaue Textur mit Frames, Angaben zum
// Uploader und Freigeben/Ablehnen/Löschen – auch per Tastatur (A, D, ←/→, Esc).
const props = defineProps<{
  cape: AdminCape
  /** Position in der Liste (0-basiert) und Länge der Liste. */
  index: number
  total: number
  busy: boolean
  error: string
}>()
const emit = defineEmits<{
  close: []
  prev: []
  next: []
  approve: []
  reject: [reason: string]
  delete: []
}>()

const { m, fill, lang } = useLang()
const { api } = useAdmin()
const r = computed(() => m.value.admin.review)
const locale = computed(() => (lang.value === 'en' ? 'en-GB' : lang.value))

/** Texturen immer über diese Seite laden (Cookie + CSP), auch wenn die API eine andere Adresse nennt. */
function localUrl(url: string): string {
  try {
    const u = new URL(url, location.origin)
    return u.pathname + u.search
  } catch {
    return url
  }
}
const texture = computed(() => localUrl(props.cape.url))
const viewerCape = computed<CapeTexture>(() => ({
  id: props.cape.id,
  url: texture.value,
  frames: props.cape.frames,
  frameTimeMs: props.cape.frameTimeMs,
}))

// --- Frames: eine Uhr für 2D und 3D ---------------------------------------------------------
const frame = ref(0)
const playing = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

function stopTimer() {
  if (timer) clearInterval(timer)
  timer = null
}
function startTimer() {
  stopTimer()
  const { frames, frameTimeMs } = props.cape
  if (!playing.value || frames <= 1 || !frameTimeMs) return
  const tick = () => (frame.value = trsFrameIndex(Date.now(), frames, frameTimeMs))
  tick()
  timer = setInterval(tick, Math.max(20, Math.min(frameTimeMs / 2, 250)))
}
watch(playing, startTimer)

// --- Uploader: Gesicht aus dem Skin (textures.minecraft.net, nur als <img> zugeschnitten) ------
const SKIN_URL = /^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{1,128}$/
const skinCache = useState<Record<string, string | null>>('admin-skin-cache', () => ({}))
const skinUrl = ref<string | null>(null)
const skinFailed = ref(false)

async function loadSkin(uuid: string | undefined) {
  skinUrl.value = null
  skinFailed.value = false
  if (!uuid) return
  if (uuid in skinCache.value) {
    skinUrl.value = skinCache.value[uuid] ?? null
    return
  }
  try {
    const res = await api<{ textureUrl: string | null }>(`/v1/admin/users/${encodeURIComponent(uuid)}/skin`)
    const url = typeof res.textureUrl === 'string' && SKIN_URL.test(res.textureUrl) ? res.textureUrl : null
    skinCache.value[uuid] = url
    if (props.cape.owner?.uuid === uuid) skinUrl.value = url
  } catch (e) {
    // Kein Gesicht – dann eben der Buchstabe. Unbekannte Konten merken, Ausfälle/Limits nicht.
    if ((e as { statusCode?: number }).statusCode === 404) skinCache.value[uuid] = null
  }
}

const ownerName = computed(() => props.cape.owner?.name || props.cape.owner?.uuid || '–')
const initial = computed(() => (props.cape.owner?.name || '?').slice(0, 1).toUpperCase())
/** Farbe des Ersatz-Kästchens aus der UUID (immer gleich für denselben Spieler). */
const fallbackColor = computed(() => {
  const u = props.cape.owner?.uuid ?? ''
  let h = 0
  for (const ch of u) h = (h * 31 + ch.charCodeAt(0)) % 360
  return `hsl(${h} 45% 38%)`
})

// --- Angaben --------------------------------------------------------------------------------
function dateTime(iso: string | null): string {
  if (!iso) return '–'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '–'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
}
const size = computed(() => formatBytes(props.cape.bytes, locale.value))
const resolution = computed(() => fill(r.value.resolutionValue, { w: props.cape.width, h: props.cape.height, k: props.cape.scale }))
const animation = computed(() =>
  props.cape.frames > 1
    ? fill(r.value.animationValue, { frames: props.cape.frames, ms: props.cape.frameTimeMs ?? '–' })
    : r.value.still,
)
const stats = computed(() => {
  const s = props.cape.ownerStats
  if (!s) return []
  return [
    { label: r.value.statUploads, value: s.uploads },
    { label: r.value.statApproved, value: s.approved },
    { label: r.value.statPending, value: s.pending },
    { label: r.value.statRejected, value: s.rejected, warn: s.rejected > 0 },
  ]
})
function reportLabel(key: string): string {
  const labels = r.value.reportReasons as Record<string, string>
  return labels[key] ?? key
}
const statusClass = computed(() => ({
  pending: 'bg-lamp-900 text-lamp-300',
  approved: 'bg-ok/15 text-ok',
  rejected: 'bg-redstone-900 text-redstone-300',
})[props.cape.status])

// --- Ablehnen / Löschen ---------------------------------------------------------------------
type ReasonKey = keyof Messages['admin']['review']['reasons']
const REASONS: ReasonKey[] = ['inappropriate', 'copyright', 'quality', 'format', 'other']
const rejectOpen = ref(false)
const reasonKey = ref<ReasonKey | ''>('')
const reasonText = ref('')
const confirmDelete = ref(false)
const reasonList = shallowRef<HTMLElement | null>(null)
const reasonField = shallowRef<HTMLTextAreaElement | null>(null)
const canReject = computed(() => reasonText.value.trim().length > 0)

function choose(key: ReasonKey) {
  reasonKey.value = key
  // Vorlage in der Sprache des Admins; „Sonstiges“ = eigener Text.
  reasonText.value = r.value.reasons[key].text
  if (key === 'other') void nextTick(() => reasonField.value?.focus())
}

function openReject() {
  confirmDelete.value = false
  rejectOpen.value = true
  void nextTick(() => {
    const input = reasonList.value?.querySelector<HTMLInputElement>('input:checked') ?? reasonList.value?.querySelector<HTMLInputElement>('input')
    input?.focus()
  })
}

function submitReject() {
  if (!canReject.value || props.busy) return
  // Zeilenumbrüche o. Ä. lehnt die API ab (Steuerzeichen) – zu Leerzeichen machen.
  emit('reject', reasonText.value.replace(/\s+/g, ' ').trim().slice(0, 200))
}

function approve() {
  if (!props.busy) emit('approve')
}

function resetFor() {
  frame.value = 0
  playing.value = props.cape.frames > 1 && !!props.cape.frameTimeMs
  rejectOpen.value = false
  reasonKey.value = ''
  reasonText.value = ''
  confirmDelete.value = false
  startTimer()
  void loadSkin(props.cape.owner?.uuid)
}
watch(() => props.cape.id, resetFor)

// --- Tastatur und Fokus ---------------------------------------------------------------------
const panel = shallowRef<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null

/** Tippt der Admin gerade (Textfeld, Auswahl)? Dann keine Kürzel. */
function isTyping(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  if (el.isContentEditable || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement) return true
  return el instanceof HTMLInputElement && !['radio', 'checkbox', 'range', 'button', 'submit', 'reset'].includes(el.type)
}

function onKey(e: KeyboardEvent) {
  if (e.defaultPrevented) return
  if (e.key === 'Escape') {
    e.preventDefault()
    if (confirmDelete.value) confirmDelete.value = false
    else if (rejectOpen.value) rejectOpen.value = false
    else emit('close')
    return
  }
  if (e.ctrlKey || e.metaKey || e.altKey || isTyping(e.target)) return
  const key = e.key.toLowerCase()
  if (key === 'arrowleft' || key === 'arrowright') {
    // Pfeile am Zoom-Regler und in der Grund-Auswahl bleiben dort.
    if (e.target instanceof HTMLInputElement && (e.target.type === 'range' || e.target.type === 'radio')) return
    e.preventDefault()
    if (key === 'arrowleft' && props.index > 0) emit('prev')
    if (key === 'arrowright' && props.index < props.total - 1) emit('next')
    return
  }
  if (confirmDelete.value || e.repeat) return
  if (key === 'a') {
    e.preventDefault()
    // Während der Ablehnung kein versehentliches Freigeben.
    if (!rejectOpen.value) approve()
  } else if (key === 'd') {
    e.preventDefault()
    if (!rejectOpen.value) openReject()
    else if (canReject.value) submitReject()
    else openReject()
  }
}

/** Tab bleibt im Dialog. */
function trapTab(e: KeyboardEvent) {
  if (e.key !== 'Tab' || !panel.value) return
  const items = [...panel.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter((el) => el.offsetParent !== null)
  if (!items.length) return
  const first = items[0]!
  const last = items[items.length - 1]!
  if (e.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}

let overflow = ''
onMounted(() => {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  overflow = document.documentElement.style.overflow
  document.documentElement.style.overflow = 'hidden'
  window.addEventListener('keydown', onKey)
  resetFor()
  void nextTick(() => panel.value?.focus())
})
onBeforeUnmount(() => {
  stopTimer()
  window.removeEventListener('keydown', onKey)
  document.documentElement.style.overflow = overflow
  if (returnFocus && document.contains(returnFocus)) returnFocus.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="backdrop" @mousedown.self="emit('close')">
      <div
        ref="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cape-review-title"
        tabindex="-1"
        class="review-panel card w-full max-w-6xl animate-pop p-4 sm:p-6"
        @keydown="trapTab"
      >
        <!-- Kopf -->
        <header class="flex flex-wrap items-center gap-3">
          <div class="min-w-0 flex-1">
            <p class="text-xs tracking-[0.18em] text-base-400 uppercase">
              {{ r.dialogTitle }} · {{ fill(r.position, { n: index + 1, total }) }}
            </p>
            <h2 id="cape-review-title" class="display mt-1 truncate text-2xl text-base-50">{{ cape.name || cape.id }}</h2>
          </div>
          <span class="badge" :class="statusClass">{{ r.status[cape.status] }}</span>
          <span v-if="cape.reports.count" class="badge bg-lamp-900 text-lamp-300">
            <SiteIcon name="flag" class="size-3" />{{ cape.reports.count }}
          </span>
          <div class="flex gap-1.5">
            <button type="button" class="btn-icon" :disabled="index === 0" :aria-label="r.prev" :title="`${r.prev} (←)`" @click="emit('prev')">
              <SiteIcon name="back" class="size-4" />
            </button>
            <button type="button" class="btn-icon" :disabled="index >= total - 1" :aria-label="r.next" :title="`${r.next} (→)`" @click="emit('next')">
              <SiteIcon name="arrow" class="size-4" />
            </button>
            <button type="button" class="btn-icon" :aria-label="m.common.close" :title="`${m.common.close} (Esc)`" @click="emit('close')">
              <SiteIcon name="close" class="size-4" />
            </button>
          </div>
        </header>

        <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>

        <div class="mt-5 grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <!-- Textur -->
          <section :aria-label="r.texture">
            <CapeTextureView
              v-model:frame="frame"
              v-model:playing="playing"
              :url="texture"
              :scale="cape.scale"
              :frames="cape.frames"
              :animated="cape.frames > 1 && !!cape.frameTimeMs"
            />
          </section>

          <!-- Vorschau und Angaben -->
          <aside class="space-y-5">
            <div class="overflow-hidden rounded-lg border border-base-800 bg-base-950">
              <CapeViewer :cape="viewerCape" :height="260" :frame="frame" />
              <p class="border-t border-base-800 px-3 py-1.5 text-[11px] text-base-400">{{ r.preview3d }}</p>
            </div>

            <div class="flex items-center gap-3">
              <div class="face" aria-hidden="true">
                <template v-if="skinUrl && !skinFailed">
                  <img :src="skinUrl" alt="" referrerpolicy="no-referrer" class="face-layer" style="left: -48px; top: -48px" @error="skinFailed = true" />
                  <img :src="skinUrl" alt="" referrerpolicy="no-referrer" class="face-layer" style="left: -240px; top: -48px" />
                </template>
                <span v-else class="face-fallback" :style="{ background: fallbackColor }">{{ initial }}</span>
              </div>
              <div class="min-w-0">
                <p class="text-xs text-base-400">{{ r.uploader }}</p>
                <p class="truncate font-semibold text-base-50">{{ ownerName }}</p>
                <p v-if="cape.owner" class="truncate font-mono text-[11px] text-base-400">{{ cape.owner.uuid }}</p>
              </div>
            </div>

            <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
              <dt class="text-base-400">{{ r.uploaded }}</dt><dd class="text-base-50">{{ dateTime(cape.createdAt) }}</dd>
              <dt class="text-base-400">{{ r.size }}</dt><dd class="tabular-nums text-base-50">{{ size }}</dd>
              <dt class="text-base-400">{{ r.resolution }}</dt><dd class="tabular-nums text-base-50">{{ resolution }}</dd>
              <dt class="text-base-400">{{ r.animation }}</dt><dd class="tabular-nums text-base-50">{{ animation }}</dd>
              <template v-if="cape.reviewedAt">
                <dt class="text-base-400">{{ r.reviewed }}</dt><dd class="text-base-50">{{ dateTime(cape.reviewedAt) }}</dd>
              </template>
              <template v-if="cape.rejectReason">
                <dt class="text-base-400">{{ r.earlierReason }}</dt><dd class="break-words text-base-50">{{ cape.rejectReason }}</dd>
              </template>
            </dl>

            <div v-if="stats.length">
              <p class="text-xs text-base-400">{{ r.ownerStats }}</p>
              <div class="mt-1.5 grid grid-cols-4 gap-1.5">
                <div v-for="s in stats" :key="s.label" class="rounded-md bg-base-950 px-2 py-1.5 text-center" :class="{ 'text-redstone-300': s.warn }">
                  <p class="display text-lg tabular-nums" :class="s.warn ? 'text-redstone-300' : 'text-base-50'">{{ s.value }}</p>
                  <p class="text-[10px] text-base-400">{{ s.label }}</p>
                </div>
              </div>
            </div>

            <div>
              <p class="text-xs text-base-400">{{ r.reports }}</p>
              <p v-if="!cape.reports.count" class="mt-1 text-sm text-base-400">{{ r.noReports }}</p>
              <div v-else class="mt-1.5 flex flex-wrap gap-1.5">
                <span v-for="(n, key) in cape.reports.reasons" :key="key" class="chip">{{ reportLabel(String(key)) }} × {{ n }}</span>
              </div>
            </div>
          </aside>
        </div>

        <!-- Entscheidung -->
        <div class="mt-6 border-t border-base-800 pt-5">
          <form v-if="rejectOpen" @submit.prevent="submitReject">
            <fieldset>
              <legend class="text-sm font-semibold text-base-50">{{ r.rejectTitle }}</legend>
              <div ref="reasonList" class="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-5">
                <label v-for="k in REASONS" :key="k" class="reason-opt" :class="{ 'reason-opt-on': reasonKey === k }">
                  <input type="radio" name="reject-reason" class="accent-redstone-500" :value="k" :checked="reasonKey === k" @change="choose(k)" />
                  <span>{{ r.reasons[k].label }}</span>
                </label>
              </div>
            </fieldset>
            <label class="label mt-4" for="reject-text">{{ r.reasonText }}</label>
            <textarea id="reject-text" ref="reasonField" v-model="reasonText" class="field resize-none" rows="2" maxlength="200" />
            <p class="mt-1 text-right text-[11px] tabular-nums text-base-400">{{ reasonText.length }}/200</p>
            <div class="mt-2 flex flex-wrap gap-2">
              <button type="submit" class="btn btn-danger" :disabled="!canReject || busy">
                {{ r.rejectConfirm }}<kbd class="key">D</kbd>
              </button>
              <button type="button" class="btn btn-ghost" @click="rejectOpen = false">{{ r.cancel }}<kbd class="key">Esc</kbd></button>
            </div>
          </form>

          <div v-else-if="confirmDelete" class="flex flex-wrap items-center gap-3">
            <p class="text-sm text-redstone-300">{{ r.deleteConfirm }}</p>
            <button type="button" class="btn btn-danger" :disabled="busy" @click="emit('delete')">
              <SiteIcon name="trash" class="size-4" />{{ r.deleteNow }}
            </button>
            <button type="button" class="btn btn-ghost" @click="confirmDelete = false">{{ r.cancel }}</button>
          </div>

          <div v-else class="flex flex-wrap items-center gap-2">
            <button type="button" class="btn btn-primary" :disabled="busy" @click="approve">
              <SiteIcon name="check" class="size-4" />{{ m.admin.review.approve }}<kbd class="key key-on-primary">A</kbd>
            </button>
            <button type="button" class="btn btn-ghost" :disabled="busy" @click="openReject">
              <SiteIcon name="close" class="size-4" />{{ m.admin.review.reject }}<kbd class="key">D</kbd>
            </button>
            <button type="button" class="btn btn-danger sm:ml-auto" :disabled="busy" @click="confirmDelete = true; rejectOpen = false">
              <SiteIcon name="trash" class="size-4" />{{ m.admin.review.delete }}
            </button>
          </div>

          <p class="mt-4 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-base-400">
            <span><kbd class="key">A</kbd> {{ r.keyApprove }}</span>
            <span><kbd class="key">D</kbd> {{ r.keyReject }}</span>
            <span><kbd class="key">←</kbd><kbd class="key">→</kbd> {{ r.keyNav }}</span>
            <span><kbd class="key">Esc</kbd> {{ r.keyClose }}</span>
          </p>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  overflow-y: auto;
  padding: 0.75rem;
  background: rgb(0 0 0 / 0.72);
  backdrop-filter: blur(3px);
}
@media (min-width: 640px) {
  .backdrop {
    padding: 2rem 1.5rem;
  }
}
/* Der Dialog selbst bekommt den Fokus nur als Startpunkt – ohne Rahmen. */
.review-panel:focus,
.review-panel:focus-visible {
  outline: none;
}
/* Gesicht: 8×8 Kopf + Hut-Ebene, 6-fach, per CSS aus dem Skin geschnitten (kein Auslesen fremder Bilder). */
.face {
  position: relative;
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  overflow: hidden;
  border-radius: 0.375rem;
  background: var(--color-base-950);
}
.face-layer {
  position: absolute;
  width: 384px;
  max-width: none;
  height: auto;
  image-rendering: pixelated;
}
.face-fallback {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  font-family: var(--font-display);
  font-size: 1.5rem;
  color: #fff;
}
.reason-opt {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  font-size: 0.8125rem;
  color: var(--color-base-200);
  cursor: pointer;
}
.reason-opt:hover {
  border-color: var(--color-base-600);
}
.reason-opt-on {
  border-color: var(--color-redstone-500);
  color: var(--color-base-50);
}
.key {
  display: inline-grid;
  place-items: center;
  min-width: 1.25rem;
  height: 1.25rem;
  margin-left: 0.25rem;
  padding: 0 0.3rem;
  border-radius: 0.25rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-850);
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  line-height: 1;
  color: var(--color-base-200);
}
.key-on-primary {
  border-color: rgb(255 255 255 / 0.35);
  background: rgb(0 0 0 / 0.18);
  color: #fff;
}
</style>
