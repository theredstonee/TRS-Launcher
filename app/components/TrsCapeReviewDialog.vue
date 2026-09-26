<script setup lang="ts">
import type { TrsAdminCape, TrsReportReason } from '~/utils/trs'

// „Umhänge prüfen“ im Detail: 3D-Vorschau am Spieler (Animation läuft), große
// pixelgenaue Textur mit Zoom und allen Frames, Infos zum Hochlader und die
// Entscheidung – mit Tasten: A freigeben, D ablehnen, ←/→ blättern, Esc schließen.
const props = defineProps<{
  capes: TrsAdminCape[]
  startId: string
  /** Liste neu laden (ohne Ladeanzeige), nachdem etwas entschieden wurde. */
  reload: () => Promise<void>
}>()
const emit = defineEmits<{ close: [] }>()

const toasts = useToasts()
/** Löschen dürfen nur Admins (Moderatoren prüfen nur). */
const team = useTeam()

const currentId = ref(props.startId)
/** Letzter bekannter Stand – bleibt sichtbar, während die Liste neu lädt. */
const current = ref<TrsAdminCape | null>(props.capes.find((c) => c.id === props.startId) ?? null)
const index = computed(() => props.capes.findIndex((c) => c.id === currentId.value))
const busy = ref<'approve' | 'reject' | 'delete' | null>(null)

watch(
  () => props.capes,
  (list) => {
    const found = list.find((c) => c.id === currentId.value)
    if (found) current.value = found
  },
)

function go(step: number) {
  if (!props.capes.length) return
  const i = index.value < 0 ? 0 : (index.value + step + props.capes.length) % props.capes.length
  select(props.capes[i]!.id)
}

function select(id: string) {
  currentId.value = id
  current.value = props.capes.find((c) => c.id === id) ?? current.value
  rejectOpen.value = false
  confirmDelete.value = false
  frame.value = 0
  playing.value = true
  fitZoom()
}

/** Nach einer Entscheidung: nächster Umhang der (neu geladenen) Liste – oder schließen. */
async function done(before: string[]) {
  const at = before.indexOf(currentId.value)
  await props.reload()
  const remaining = props.capes.map((c) => c.id)
  const still = remaining.includes(currentId.value)
  const next = still ? before[at + 1] : before.slice(at + 1).find((id) => remaining.includes(id))
  const fallback = next && remaining.includes(next) ? next : remaining.find((id) => id !== currentId.value)
  if (!still && !fallback) {
    emit('close')
    return
  }
  select(fallback ?? currentId.value)
}

async function act(kind: 'approve' | 'reject' | 'delete', action: () => Promise<void>, toast: string) {
  if (busy.value) return
  const before = props.capes.map((c) => c.id)
  busy.value = kind
  try {
    await action()
    toasts.ok(toast)
    await done(before)
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

const canApprove = computed(() => !!current.value && (current.value.status !== 'approved' || current.value.reports.count > 0))
const canReject = computed(() => !!current.value && current.value.status !== 'rejected')

function approve() {
  const cape = current.value
  if (!cape || !canApprove.value) return
  void act('approve', () => backend.trs.adminApprove(cape.id), t('admin.toasts.approved', { name: cape.name }))
}

// --- Ablehnen mit Grund -----------------------------------------------------------------

const presets = ['inappropriate', 'copyright', 'quality', 'format', 'other'] as const
type Preset = (typeof presets)[number]
const rejectOpen = ref(false)
const preset = ref<Preset | null>(null)
const reason = ref('')
const reasonError = ref<string | null>(null)
const reasonBox = ref<HTMLElement | null>(null)

function choose(p: Preset) {
  preset.value = p
  reason.value = p === 'other' ? '' : t(`admin.review.dialog.presets.${p}`)
}

async function openReject() {
  if (!canReject.value) return
  rejectOpen.value = true
  reasonError.value = null
  await nextTick()
  reasonBox.value?.querySelector<HTMLInputElement>('input[type="radio"]')?.focus()
}

function reject() {
  const cape = current.value
  if (!cape || !canReject.value) return
  const parsed = trsNoteSchema.safeParse(reason.value)
  if (!parsed.success) {
    reasonError.value = firstIssue(parsed.error)
    return
  }
  reasonError.value = null
  void act('reject', () => backend.trs.adminReject(cape.id, parsed.data || null), t('admin.toasts.rejected', { name: cape.name }))
}

const confirmDelete = ref(false)
function remove() {
  const cape = current.value
  if (!cape) return
  if (!confirmDelete.value) {
    confirmDelete.value = true
    return
  }
  void act('delete', () => backend.trs.adminDeleteCape(cape.id), t('admin.toasts.capeDeleted'))
}

// --- Tasten -----------------------------------------------------------------------------

function typing(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el) return false
  if (el.isContentEditable) return true
  if (el instanceof HTMLInputElement) return !['radio', 'checkbox', 'range', 'button'].includes(el.type)
  return el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement
}

function onKey(e: KeyboardEvent) {
  if (e.ctrlKey || e.metaKey || e.altKey || typing(e.target)) return
  const key = e.key.toLowerCase()
  if (key === 'a') approve()
  else if (key === 'd') {
    if (rejectOpen.value) reject()
    else void openReject()
  } else if (e.key === 'ArrowRight') go(1)
  else if (e.key === 'ArrowLeft') go(-1)
  else return
  e.preventDefault()
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  stop()
})

// --- Textur, Frames, Zoom ---------------------------------------------------------------

const image = shallowRef<HTMLImageElement | null>(null)
const big = ref<HTMLCanvasElement | null>(null)
const thumbs = ref<HTMLCanvasElement[]>([])
const frame = ref(0)
const playing = ref(true)
const zoom = ref(1)
const stage = ref<HTMLElement | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

const frames = computed(() => current.value?.frames ?? 1)
const frameTime = computed(() => current.value?.frameTimeMs ?? null)

function stop() {
  if (timer) clearInterval(timer)
  timer = null
}

function fitZoom() {
  const cape = current.value
  const width = stage.value?.clientWidth ?? 520
  if (!cape) return
  zoom.value = Math.max(0.25, Math.min(8, Math.floor(((width - 16) / cape.width) * 4) / 4))
}

function drawFrame(canvas: HTMLCanvasElement | null | undefined, f: number) {
  const img = image.value
  const cape = current.value
  if (!canvas || !img || !cape) return
  canvas.width = cape.width
  canvas.height = cape.height
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.imageSmoothingEnabled = false
  ctx.clearRect(0, 0, cape.width, cape.height)
  ctx.drawImage(img, 0, f * cape.height, cape.width, cape.height, 0, 0, cape.width, cape.height)
}

function tick() {
  if (playing.value && frames.value > 1) frame.value = trsFrameIndex(Date.now(), frames.value, frameTime.value)
}

watch(frame, (f) => drawFrame(big.value, f))

watch(
  () => current.value?.texture,
  (texture) => {
    stop()
    image.value = null
    if (!texture) return
    const img = new Image()
    img.onload = async () => {
      if (current.value?.texture !== texture) return
      image.value = img
      await nextTick()
      drawFrame(big.value, frame.value)
      thumbs.value.forEach((c, i) => drawFrame(c, i))
      if (frames.value > 1) timer = setInterval(tick, Math.max(20, Math.min((frameTime.value ?? 100) / 2, 250)))
    }
    img.src = texture
  },
  { immediate: true },
)

onMounted(fitZoom)

function pickFrame(i: number) {
  playing.value = false
  frame.value = i
}

function stats(cape: TrsAdminCape): string {
  const s = cape.ownerStats
  if (!s) return '–'
  return t('admin.review.dialog.ownerStats', { uploads: s.uploads, approved: s.approved, pending: s.pending, rejected: s.rejected })
}

const reportReasons: readonly string[] = ['inappropriate', 'copyright', 'impersonation', 'other'] satisfies TrsReportReason[]
function reportSummary(reasons: Record<string, number>): string {
  return Object.entries(reasons)
    .map(([r, n]) => `${reportReasons.includes(r) ? t(`admin.review.reasons.${r as TrsReportReason}`) : r} (${n})`)
    .join(', ')
}
</script>

<template>
  <BaseDialog :title="current ? t('admin.review.dialog.title', { name: current.name }) : t('admin.tabs.capes')" huge @close="emit('close')">
    <div v-if="current" class="grid gap-5 lg:grid-cols-[17rem_minmax(0,1fr)]" data-testid="admin-cape-review">
      <!-- Links: 3D + Hochlader -->
      <div class="space-y-3">
        <div class="rounded-lg bg-base-900">
          <SkinViewer
            :skin="null"
            :cape="current.texture"
            :cape-frames="current.frames"
            :cape-frame-time="current.frameTimeMs"
            animation="walk"
            :height="280"
          />
        </div>
        <div class="card flex items-center gap-3 p-3">
          <span class="size-10 shrink-0 overflow-hidden rounded bg-base-800">
            <PlayerFace v-if="current.owner" :uuid="current.owner.uuid" :name="current.owner.name" />
          </span>
          <div class="min-w-0 text-xs">
            <p class="truncate text-sm font-semibold text-base-50">{{ current.owner?.name ?? t('admin.review.deletedAccount') }}</p>
            <p class="text-base-400">{{ stats(current) }}</p>
          </div>
        </div>
        <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
          <dt class="text-base-400">{{ t('admin.review.dialog.uploaded') }}</dt>
          <dd class="text-base-100">{{ trsDate(current.createdAt) }}</dd>
          <dt class="text-base-400">{{ t('admin.review.dialog.fileSize') }}</dt>
          <dd class="text-base-100">{{ current.bytes !== null ? formatFileSize(current.bytes) : '–' }}</dd>
          <dt class="text-base-400">{{ t('admin.review.dialog.resolution') }}</dt>
          <dd class="text-base-100">{{ current.width }}×{{ current.height }} (×{{ current.scale }})</dd>
          <dt class="text-base-400">{{ t('admin.review.dialog.frames') }}</dt>
          <dd class="text-base-100">
            {{ current.frames }}<template v-if="current.frameTimeMs"> · {{ t('admin.review.dialog.frameTime', { ms: current.frameTimeMs }) }}</template>
          </dd>
          <template v-if="current.reports.count">
            <dt class="text-warn">{{ t('admin.review.lists.reported') }}</dt>
            <dd class="text-warn">{{ t('admin.review.reported', { count: current.reports.count, reasons: reportSummary(current.reports.reasons) }) }}</dd>
          </template>
          <template v-if="current.rejectReason">
            <dt class="text-base-400">{{ t('admin.review.lists.rejected') }}</dt>
            <dd class="text-redstone-300">{{ current.rejectReason }}</dd>
          </template>
        </dl>
      </div>

      <!-- Rechts: Textur -->
      <div class="min-w-0 space-y-3">
        <div ref="stage" class="max-h-[22rem] overflow-auto rounded-lg bg-[repeating-conic-gradient(#1b1c22_0_25%,#23242b_0_50%)] bg-[length:16px_16px] p-2">
          <canvas
            ref="big"
            class="block [image-rendering:pixelated]"
            :style="{ width: `${current.width * zoom}px`, height: `${current.height * zoom}px` }"
            role="img"
            :aria-label="t('admin.review.fullTexture')"
          />
        </div>
        <div class="flex flex-wrap items-center gap-3 text-xs text-base-400">
          <span>{{ t('admin.review.dialog.zoom') }}</span>
          <input v-model.number="zoom" type="range" min="0.25" max="8" step="0.25" class="w-40 accent-redstone-500" :aria-label="t('admin.review.dialog.zoom')" />
          <span class="tabular-nums">{{ zoom }}×</span>
          <button class="btn btn-ghost px-2 py-1 text-xs" @click="fitZoom">{{ t('admin.review.dialog.fit') }}</button>
          <template v-if="current.frames > 1">
            <span class="ml-auto tabular-nums">{{ t('admin.review.dialog.frameOf', { n: frame + 1, total: current.frames }) }}</span>
            <button class="btn btn-ghost px-2 py-1 text-xs" :aria-pressed="playing" @click="playing = !playing">
              {{ playing ? t('admin.review.dialog.pause') : t('admin.review.dialog.play') }}
            </button>
          </template>
        </div>
        <div v-if="current.frames > 1" class="flex gap-1.5 overflow-x-auto pb-1" role="listbox" :aria-label="t('admin.review.dialog.frames')">
          <button
            v-for="i in current.frames"
            :key="i"
            class="shrink-0 rounded border p-0.5"
            :class="frame === i - 1 ? 'border-redstone-500' : 'border-base-800 hover:border-base-600'"
            role="option"
            :aria-selected="frame === i - 1"
            :aria-label="t('admin.review.dialog.frameOf', { n: i, total: current.frames })"
            @click="pickFrame(i - 1)"
          >
            <canvas :ref="(el) => { if (el) thumbs[i - 1] = el as HTMLCanvasElement }" class="block h-8 w-16 [image-rendering:pixelated]" />
          </button>
        </div>

        <!-- Ablehnen: Grund wählen -->
        <div v-if="rejectOpen" ref="reasonBox" class="card space-y-2 p-3">
          <p class="text-xs font-semibold text-base-100">{{ t('admin.review.dialog.reasonTitle') }}</p>
          <div class="grid gap-1 sm:grid-cols-2" role="radiogroup">
            <label v-for="p in presets" :key="p" class="flex cursor-pointer items-center gap-2 text-xs text-base-200">
              <input type="radio" name="reject-preset" class="accent-redstone-500" :checked="preset === p" @change="choose(p)" />
              {{ t(`admin.review.dialog.presetLabels.${p}`) }}
            </label>
          </div>
          <input
            v-model="reason"
            class="field"
            maxlength="200"
            :placeholder="t('admin.dialogs.rejectPlaceholder')"
            :aria-label="t('admin.dialogs.rejectReasonLabel')"
            @keydown.enter="reject"
          />
          <p v-if="reasonError" role="alert" class="text-xs text-redstone-300">{{ reasonError }}</p>
        </div>
      </div>
    </div>

    <template #actions>
      <p class="mr-auto hidden text-[11px] text-base-600 sm:block">{{ t('admin.review.dialog.keys') }}</p>
      <button class="btn btn-ghost px-3" :disabled="capes.length < 2" :aria-label="t('admin.review.dialog.prev')" @click="go(-1)">←</button>
      <span class="self-center text-xs text-base-400 tabular-nums">{{ index + 1 }} / {{ capes.length }}</span>
      <button class="btn btn-ghost px-3" :disabled="capes.length < 2" :aria-label="t('admin.review.dialog.next')" @click="go(1)">→</button>
      <button v-if="team.isAdmin.value" class="btn btn-ghost hover:text-redstone-300" :disabled="!!busy" @click="remove">
        {{ confirmDelete ? t('admin.review.dialog.confirmDelete') : t('common.actions.delete') }}
      </button>
      <template v-if="canReject">
        <button v-if="!rejectOpen" class="btn btn-ghost" :disabled="!!busy" @click="openReject">{{ t('admin.review.reject') }} (D)</button>
        <button v-else class="btn btn-danger" :disabled="!!busy" data-testid="admin-review-reject" @click="reject">
          {{ busy === 'reject' ? t('admin.review.dialog.working') : t('admin.review.dialog.rejectNow') }} (D)
        </button>
      </template>
      <button v-if="canApprove" class="btn btn-primary" :disabled="!!busy" data-testid="admin-review-approve" @click="approve">
        {{ busy === 'approve' ? t('admin.review.dialog.working') : t('admin.review.approve') }} (A)
      </button>
    </template>
  </BaseDialog>
</template>
