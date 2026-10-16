<script setup lang="ts">
import type { Rgba } from '~/utils/capeImage'
import { base64Bytes, encodePng } from '~/utils/capeExport'
import {
  SYMBOL_FRAME_MS_DEFAULT,
  SYMBOL_MAX,
  SYMBOL_MAX_BYTES,
  SYMBOL_MIN,
  buildSymbolStrip,
  symbolFrameMs,
  symbolSize,
  symbolVisible,
  type CreatorSymbol,
} from '~/utils/creator'

// Neues Fan-Symbol: Bild wählen (PNG, GIF oder APNG – der Kern dekodiert die
// Datei), mittig quadratisch zuschneiden, Größe 16–64 px wählen, bei Animation
// höchstens `maxFrames` Bilder und das Tempo. Vorschau in 64/32/16 px und im
// Spiel; dann als PNG-Streifen hochladen (geht in die Prüfung).
const props = defineProps<{ maxFrames: number; player: string }>()
const emit = defineEmits<{ close: []; uploaded: [] }>()
const store = useCreatorStore()

const frames = shallowRef<Rgba[]>([])
const sourceName = ref('')
const size = ref(32)
const frameMs = ref(SYMBOL_FRAME_MS_DEFAULT)
const picking = ref(false)
const uploading = ref(false)
const error = ref<string | null>(null)

function loadRgba(url: string): Promise<Rgba> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const canvas = document.createElement('canvas')
      canvas.width = img.naturalWidth
      canvas.height = img.naturalHeight
      const ctx = canvas.getContext('2d')
      if (!ctx) return reject(new Error('canvas'))
      ctx.drawImage(img, 0, 0)
      const data = ctx.getImageData(0, 0, canvas.width, canvas.height)
      resolve({ width: data.width, height: data.height, data: data.data })
    }
    img.onerror = () => reject(new Error('image'))
    img.src = url
  })
}

async function pick() {
  picking.value = true
  error.value = null
  try {
    const sources = await backend.trs.pickCapeSources()
    const source = sources?.[0]
    if (!source) return
    if (source.kind === 'studio') {
      error.value = t('creator.symbol.dialog.unsupported')
      return
    }
    const urls = source.kind === 'image' ? [source.dataUrl] : source.frames
    const loaded = await Promise.all(urls.map(loadRgba))
    frames.value = loaded
    sourceName.value = source.name
    size.value = symbolSize(loaded[0]!.width, loaded[0]!.height)
    const kept = Math.min(loaded.length, props.maxFrames)
    frameMs.value = source.kind === 'animation' ? symbolFrameMs(source.durationMs, kept) : SYMBOL_FRAME_MS_DEFAULT
  } catch (e) {
    error.value = e instanceof BackendError ? errorMessage(e) : t('creator.symbol.dialog.loadFailed')
  } finally {
    picking.value = false
  }
}

interface Result {
  b64: string
  url: string
  bytes: number
  frames: number
  sourceFrames: number
  visible: boolean
}
const result = shallowRef<Result | null>(null)
let token = 0

watch(
  [frames, size],
  async () => {
    const mine = ++token
    const built = buildSymbolStrip(frames.value, props.maxFrames, size.value)
    if (!built) {
      result.value = null
      return
    }
    const b64 = await encodePng(built.strip)
    if (mine !== token) return
    result.value = {
      b64,
      url: `data:image/png;base64,${b64}`,
      bytes: base64Bytes(b64),
      frames: built.frames,
      sourceFrames: built.sourceFrames,
      visible: symbolVisible(built.strip),
    }
  },
  { immediate: true },
)

const preview = computed<CreatorSymbol | null>(() =>
  result.value ? { sha256: '0'.repeat(64), size: size.value, frames: result.value.frames, frameMs: frameMs.value, image: result.value.url } : null,
)
const tooLarge = computed(() => !!result.value && result.value.bytes > SYMBOL_MAX_BYTES)
const canUpload = computed(() => !!result.value && result.value.visible && !tooLarge.value && !uploading.value)

async function upload() {
  const r = result.value
  if (!r || !canUpload.value) return
  uploading.value = true
  error.value = null
  try {
    await backend.creator.uploadSymbol(r.b64, r.frames > 1 ? frameMs.value : null, props.maxFrames, store.actingAs)
    emit('uploaded')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <BaseDialog :title="t('creator.symbol.dialog.title')" wide @close="emit('close')">
    <div class="space-y-4" data-testid="creator-symbol-dialog">
      <div class="flex flex-wrap items-center gap-3">
        <button class="btn btn-ghost" :disabled="picking" @click="pick">
          <SocialIcon name="upload" class="size-4" />{{ frames.length ? t('creator.symbol.dialog.other') : t('creator.symbol.dialog.choose') }}
        </button>
        <span v-if="sourceName" class="truncate text-xs text-base-400">{{ sourceName }}</span>
      </div>
      <p class="text-xs text-base-400">{{ maxFrames > 1 ? t('creator.symbol.dialog.hintAnimated', { n: maxFrames }) : t('creator.symbol.dialog.hintStatic') }}</p>

      <template v-if="result && preview">
        <div class="grid gap-3 sm:grid-cols-2">
          <label class="block">
            <span class="label">{{ t('creator.symbol.dialog.size') }}</span>
            <input v-model.number="size" type="range" :min="SYMBOL_MIN" :max="SYMBOL_MAX" step="1" class="w-full accent-redstone-500" :aria-label="t('creator.symbol.dialog.size')" />
            <span class="text-xs text-base-200 tabular-nums">{{ size }}×{{ size }}</span>
          </label>
          <label v-if="result.frames > 1" class="block">
            <span class="label">{{ t('creator.symbol.dialog.speed') }}</span>
            <input v-model.number="frameMs" type="range" min="50" max="1000" step="10" class="w-full accent-redstone-500" :aria-label="t('creator.symbol.dialog.speed')" />
            <span class="text-xs text-base-200 tabular-nums">{{ t('creator.symbol.dialog.speedValue', { ms: frameMs }) }}</span>
          </label>
        </div>
        <p v-if="result.sourceFrames > result.frames" class="text-[11px] text-lamp-300">
          {{ t('creator.symbol.dialog.sampled', { total: result.sourceFrames, max: result.frames }) }}
        </p>
        <div class="flex flex-wrap items-end gap-5 rounded-lg bg-base-900 p-3">
          <div v-for="px in [64, 32, 16]" :key="px" class="text-center">
            <CreatorSymbol :image="preview.image" :frames="preview.frames" :frame-ms="preview.frameMs" :px="px" />
            <p class="mt-1 text-[11px] text-base-400">{{ px }}×{{ px }}</p>
          </div>
          <p class="ml-auto text-[11px] tabular-nums" :class="tooLarge ? 'text-redstone-300' : 'text-base-400'">
            {{ t('creator.symbol.dialog.fileSize', { size: formatFileSize(result.bytes) }) }}
          </p>
        </div>
        <CreatorIngamePreview :player="player" :symbol="preview" compact />
        <p v-if="!result.visible" class="text-xs text-redstone-300">{{ t('creator.symbol.dialog.empty') }}</p>
        <p v-if="tooLarge" class="text-xs text-redstone-300">{{ t('creator.symbol.dialog.tooLarge') }}</p>
      </template>
      <p v-if="error" role="alert" class="text-xs text-redstone-300">{{ error }}</p>
    </div>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="!canUpload" data-testid="creator-symbol-upload" @click="upload">
        {{ uploading ? t('creator.symbol.dialog.uploading') : t('creator.symbol.dialog.upload') }}
      </button>
    </template>
  </BaseDialog>
</template>
