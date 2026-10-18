<script setup lang="ts">
import type { TrsCape } from '~/utils/trs'
import { CAPE_MAX_BYTES, CAPE_MAX_SCALE, FRAME_TIME_MAX, FRAME_TIME_MIN, capeVisible } from '~/utils/capeImage'
import { expandTimeline, type CapeDoc } from '~/utils/capeEditor'
import {
  StaleExport,
  base64Bytes,
  buildUploadStrip,
  defaultOutputScale,
  encodePng,
  isExactScale,
  suggestReductions,
  type Reduction,
} from '~/utils/capeExport'

// Schritt 3 der Umhang-Werkstatt: Auflösung (automatisch hochskaliert, pixelgenau oder
// geglättet), Tempo und Name wählen; zeigt das echte Ergebnis (Größe, Frames, Datei) und
// bietet Verkleinerungen an, wenn das PNG über 20 MB läge. Das Hochskalieren läuft
// Frame für Frame, das PNG kodiert der Browser im Hintergrund.
// `fanCape`: aus dem Creator-Dashboard geöffnet – Hochladen reicht den Fan-Umhang ein (§35.3).
const props = defineProps<{ doc: CapeDoc; sourceFrames: number; fanCape?: boolean }>()
const frameTime = defineModel<number>('frameTime', { required: true })
const name = defineModel<string>('name', { required: true })
const emit = defineEmits<{ reduce: [frames: number]; uploaded: [cape: TrsCape]; fanUploaded: [] }>()

const toasts = useToasts()
const creator = useCreatorStore()

/** „HD – wie Bild“: schon in 1024×512 bearbeitet, glatt skaliert, glatte Vorschau. */
const hd = computed(() => props.doc.style === 'hd')
const scale = ref(defaultOutputScale(props.doc.scale))
const smooth = ref(false)
const expanded = computed(() => expandTimeline(props.doc.frames))
const count = computed(() => expanded.value.length)
const animated = computed(() => count.value > 1)
const fps = computed(() => (1000 / frameTime.value).toFixed(1))
/** Glätten lohnt erst ab doppelter Größe. */
const canSmooth = computed(() => scale.value >= props.doc.scale * 2)

interface Result {
  b64: string
  url: string
  bytes: number
  frames: number
  scale: number
  visible: boolean
}
const result = shallowRef<Result | null>(null)
const progress = ref<{ done: number; total: number } | null>(null)
const error = ref<string | null>(null)
let token = 0

async function compute() {
  const mine = ++token
  const stale = () => mine !== token
  progress.value = { done: 0, total: 1 }
  error.value = null
  try {
    const strip = await buildUploadStrip(expanded.value, {
      base: props.doc.scale,
      scale: scale.value,
      smooth: smooth.value && canSmooth.value,
      style: props.doc.style,
      stale,
      onProgress: (done, total) => {
        if (!stale()) progress.value = { done, total }
      },
    })
    const visible = capeVisible(strip, scale.value, count.value)
    const b64 = await encodePng(strip)
    if (stale()) return
    result.value = { b64, url: `data:image/png;base64,${b64}`, bytes: base64Bytes(b64), frames: count.value, scale: scale.value, visible }
  } catch (e) {
    if (e instanceof StaleExport || stale()) return
    console.error(e)
    error.value = t('capes.studio.export.failed')
  } finally {
    if (!stale()) progress.value = null
  }
}

let timer: ReturnType<typeof setTimeout> | null = null
watch(
  [scale, smooth, expanded],
  () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => void compute(), 250)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  token++
  if (timer) clearTimeout(timer)
})

const busy = computed(() => !!progress.value)
const tooLarge = computed(() => !!result.value && result.value.bytes > CAPE_MAX_BYTES)
const reductions = computed<Reduction[]>(() =>
  result.value && tooLarge.value
    ? suggestReductions(result.value.bytes, result.value.scale, result.value.frames, props.doc.scale, undefined, props.doc.style)
    : [],
)
const sizePercent = computed(() => Math.min(100, ((result.value?.bytes ?? 0) / CAPE_MAX_BYTES) * 100))

function applyReduction(r: Reduction) {
  if (r.kind === 'scale') scale.value = r.value
  else emit('reduce', r.value)
}

const scales = Array.from({ length: CAPE_MAX_SCALE }, (_, i) => i + 1)

// --- Hochladen ----------------------------------------------------------------------

const uploading = ref(false)
const nameError = ref<string | null>(null)
const canUpload = computed(() => !!result.value && !busy.value && !tooLarge.value && result.value.visible && !uploading.value)

async function upload() {
  const parsed = trsCapeNameSchema.safeParse(name.value)
  if (!parsed.success) {
    nameError.value = firstIssue(parsed.error)
    return
  }
  nameError.value = null
  const r = result.value
  if (!r || !canUpload.value) return
  uploading.value = true
  error.value = null
  try {
    const cape = await backend.trs.uploadCape({
      png: r.b64,
      frames: r.frames,
      frameTimeMs: r.frames > 1 ? frameTime.value : null,
      name: parsed.data || null,
    })
    toasts.ok(t('capes.toasts.uploaded'))
    emit('uploaded', cape)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    uploading.value = false
  }
}

// --- Als Fan-Umhang einreichen (Creator ab Partner, §35.3) ------------------------------

/** `standard` (Partner: 512×256 bzw. kleiner, ein Bild) oder `hd` (Top: bis 1024×512, animiert). */
const fanMode = computed(() => creator.fanCapeMode)
/** Warum der Streifen nicht als Fan-Umhang geht (`null` = passt). */
const fanProblem = computed<string | null>(() => {
  const r = result.value
  if (!fanMode.value || !r) return null
  if (fanMode.value === 'standard' && (r.frames > 1 || r.scale > 8)) return t('creator.cape.partnerLimit')
  return null
})
const canUploadFan = computed(() => !!fanMode.value && canUpload.value && !fanProblem.value)

async function uploadFan() {
  const parsed = trsCapeNameSchema.safeParse(name.value)
  if (!parsed.success || !parsed.data) {
    nameError.value = parsed.success ? t('creator.cape.nameRequired') : firstIssue(parsed.error)
    return
  }
  nameError.value = null
  const r = result.value
  if (!r || !canUploadFan.value) return
  uploading.value = true
  error.value = null
  try {
    await backend.creator.uploadFanCape(
      { png: r.b64, frames: r.frames, frameTimeMs: r.frames > 1 ? frameTime.value : null, name: parsed.data },
      creator.actingAs,
    )
    toasts.ok(t('creator.cape.submitted'))
    void creator.loadDashboard()
    emit('fanUploaded')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    uploading.value = false
  }
}

defineExpose({ upload, canUpload, uploading, uploadFan, canUploadFan, fanMode })
</script>

<template>
  <div class="grid gap-5 md:grid-cols-[minmax(0,1fr)_17rem]" data-testid="cape-studio-export">
    <div class="min-w-0 space-y-4">
      <div class="grid gap-3 sm:grid-cols-2">
        <label class="block">
          <span class="label">{{ t('capes.editor.scale.label') }}</span>
          <select v-model.number="scale" class="field" data-testid="cape-export-scale">
            <option v-for="k in scales" :key="k" :value="k">
              {{ t('capes.editor.scale.option', { w: 64 * k, h: 32 * k, k }) }}{{ !hd && isExactScale(doc.scale, k) ? ` · ${t('capes.studio.export.exact')}` : '' }}
            </option>
          </select>
          <span class="mt-1 block text-[11px] text-base-600">
            {{ hd ? t('capes.studio.export.hdScaleHint') : t('capes.studio.export.scaleHint', { w: 64 * doc.scale, h: 32 * doc.scale }) }}
          </span>
        </label>
        <div v-if="hd" class="rounded-lg bg-base-900 p-3" data-testid="cape-export-hd">
          <p class="text-sm text-base-100">{{ t('capes.studio.style.hd') }}</p>
          <p class="text-[11px] text-base-500">{{ t('capes.studio.export.hdHint') }}</p>
        </div>
        <div v-else class="flex items-start justify-between gap-3 rounded-lg bg-base-900 p-3">
          <div class="min-w-0">
            <p class="text-sm text-base-100">{{ t('capes.studio.export.smooth') }}</p>
            <p class="text-[11px] text-base-500">{{ canSmooth ? t('capes.studio.export.smoothHint') : t('capes.studio.export.smoothOff') }}</p>
          </div>
          <ToggleSwitch v-model="smooth" :label="t('capes.studio.export.smooth')" :disabled="!canSmooth" />
        </div>
      </div>

      <div v-if="animated">
        <div class="flex items-center justify-between text-xs">
          <span class="label mb-0">{{ t('capes.editor.speed.label') }}</span>
          <span class="text-base-400 tabular-nums">{{ t('capes.editor.speed.value', { ms: frameTime, fps }) }}</span>
        </div>
        <input v-model.number="frameTime" type="range" :min="FRAME_TIME_MIN" :max="FRAME_TIME_MAX" step="10" class="mt-1 w-full accent-redstone-500" :aria-label="t('capes.editor.speed.label')" />
        <p v-if="sourceFrames > count" class="mt-1 text-[11px] text-lamp-300">{{ t('capes.editor.sampled', { total: sourceFrames, max: count }) }}</p>
      </div>

      <!-- Ergebnis -->
      <div class="space-y-2 rounded-lg border border-base-800 bg-base-900/60 p-3" role="status">
        <dl class="grid grid-cols-3 gap-2 text-center">
          <div>
            <dt class="text-[11px] text-base-500">{{ t('capes.studio.export.frameSize') }}</dt>
            <dd class="text-sm font-semibold text-base-50 tabular-nums">{{ 64 * scale }}×{{ 32 * scale }}</dd>
          </div>
          <div>
            <dt class="text-[11px] text-base-500">{{ t('capes.studio.export.frames') }}</dt>
            <dd class="text-sm font-semibold text-base-50 tabular-nums">{{ count }}</dd>
          </div>
          <div>
            <dt class="text-[11px] text-base-500">{{ t('capes.editor.sizeLabel') }}</dt>
            <dd class="text-sm font-semibold tabular-nums" :class="tooLarge ? 'text-redstone-300' : 'text-base-50'">
              {{ busy || !result ? '…' : formatFileSize(result.bytes) }}
            </dd>
          </div>
        </dl>
        <RedstoneWire v-if="busy" :percent="progress ? (progress.done / Math.max(1, progress.total)) * 100 : 0" :indeterminate="!progress || progress.done >= progress.total" />
        <div v-else class="h-1.5 overflow-hidden rounded-full bg-base-800" aria-hidden="true">
          <div class="h-full rounded-full transition-[width]" :class="tooLarge ? 'bg-redstone-500' : 'bg-emerald-500'" :style="{ width: `${sizePercent}%` }" />
        </div>
        <p class="text-[11px] text-base-400">
          {{ busy ? t('capes.studio.export.computing') : t('capes.editor.size', { size: result ? formatFileSize(result.bytes) : '–' }) }}
        </p>
      </div>

      <!-- Zu groß: Verkleinerungen anbieten -->
      <div v-if="tooLarge && !busy" class="space-y-2 rounded-lg border border-redstone-600/60 bg-redstone-900/40 p-3" data-testid="cape-export-reduce">
        <p class="text-xs text-redstone-300">{{ t('capes.studio.export.tooLarge', { size: formatFileSize(result?.bytes ?? 0) }) }}</p>
        <p v-if="hd" class="text-[11px] text-base-300">{{ t('capes.studio.export.hdTooLarge') }}</p>
        <div class="flex flex-wrap gap-2">
          <button v-for="r in reductions" :key="r.kind" type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="applyReduction(r)">
            {{
              r.kind === 'scale'
                ? t('capes.studio.export.useScale', { w: 64 * r.value, h: 32 * r.value, size: formatFileSize(r.bytes) })
                : t('capes.studio.export.useFrames', { n: r.value, size: formatFileSize(r.bytes) })
            }}
          </button>
        </div>
      </div>
      <p v-if="result && !result.visible && !busy" class="text-[11px] text-redstone-300">{{ t('capes.editor.errors.empty') }}</p>

      <label class="block">
        <span class="label">{{ t('capes.editor.nameLabel') }}</span>
        <input v-model="name" class="field" maxlength="32" :placeholder="t('capes.editor.placeholder')" @keydown.enter="upload" />
      </label>
      <p v-if="nameError" role="alert" class="text-xs text-redstone-300">{{ nameError }}</p>
      <p v-if="fanCape || fanMode" class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-[11px] text-base-400" data-testid="cape-export-fan">
        {{ fanMode === 'hd' ? t('creator.cape.workshopHd') : t('creator.cape.workshopStandard') }}
        <span v-if="fanProblem" class="mt-1 block text-redstone-300">{{ fanProblem }}</span>
      </p>
      <ul class="list-disc space-y-1 pl-4 text-[11px] text-base-400">
        <li>{{ t('capes.editor.rules.review') }}</li>
        <li>{{ t('capes.editor.rules.rights') }}</li>
      </ul>
      <p v-if="error" role="alert" class="text-xs text-redstone-300">{{ error }}</p>
    </div>

    <!-- Fertiges Ergebnis am Spieler -->
    <div class="space-y-3">
      <div class="rounded-lg bg-base-900">
        <SkinViewer
          :skin="null"
          :cape="result?.url ?? null"
          :cape-frames="result?.frames ?? 1"
          :cape-frame-time="animated ? frameTime : null"
          :cape-smooth="hd"
          focus="cape"
          animation="walk"
          :height="260"
        />
      </div>
      <div class="flex items-center gap-3">
        <div class="rounded-lg bg-base-900 p-2">
          <CapeThumb
            :texture="result?.url ?? null"
            :scale="result?.scale ?? 1"
            :frames="result?.frames ?? 1"
            :frame-time-ms="animated ? frameTime : null"
            :smooth="hd"
            :width="44"
          />
        </div>
        <p class="text-[11px] text-base-400">{{ t('capes.studio.export.final') }}</p>
      </div>
    </div>
  </div>
</template>
