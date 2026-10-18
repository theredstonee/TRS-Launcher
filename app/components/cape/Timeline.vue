<script setup lang="ts">
import { CAPE_MAX_FRAMES, FRAME_TIME_MAX, FRAME_TIME_MIN, type Rgba } from '~/utils/capeImage'
import { timelineLength, type CapeFrame } from '~/utils/capeEditor'

// Zeitleiste des Umhang-Editors: Frames als Vorschaukarten (Außenseite), ziehen zum
// Umsortieren, Standzeit je Frame (×1 …), Duplizieren, Löschen, Abspielen und Tempo.
// Standzeiten werden beim Hochladen zu wiederholten Frames (zusammen höchstens 32).
const props = defineProps<{ frames: CapeFrame[]; current: number; playing: boolean; scale: number; smooth?: boolean }>()
const frameTime = defineModel<number>('frameTime', { required: true })
const emit = defineEmits<{
  select: [index: number]
  move: [from: number, to: number]
  duplicate: [index: number]
  remove: [index: number]
  hold: [index: number, hold: number]
  togglePlay: []
}>()

const used = computed(() => timelineLength(props.frames))
const full = computed(() => used.value >= CAPE_MAX_FRAMES)
const fps = computed(() => (1000 / frameTime.value).toFixed(1))

const thumbs = new WeakMap<Rgba, string>()
/** Außenseite (1, 1, 10, 16) als kleines Bild – je Bild nur einmal berechnet. */
function thumb(img: Rgba): string {
  const cached = thumbs.get(img)
  if (cached) return cached
  const s = props.scale
  const src = document.createElement('canvas')
  src.width = img.width
  src.height = img.height
  src.getContext('2d')?.putImageData(new ImageData(new Uint8ClampedArray(img.data), img.width, img.height), 0, 0)
  // HD: doppelt so fein und glatt verkleinert (scharf auf hochauflösenden Bildschirmen).
  const k = props.smooth ? 2 : 1
  const out = document.createElement('canvas')
  out.width = 40 * k
  out.height = 64 * k
  const ctx = out.getContext('2d')
  if (ctx) {
    ctx.imageSmoothingEnabled = !!props.smooth || s > 4
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(src, s, s, 10 * s, 16 * s, 0, 0, out.width, out.height)
  }
  const url = out.toDataURL('image/png')
  thumbs.set(img, url)
  return url
}

const dragFrom = ref<number | null>(null)
const dropAt = ref<number | null>(null)
function onDrop(to: number) {
  const from = dragFrom.value
  dragFrom.value = null
  dropAt.value = null
  if (from !== null && from !== to) emit('move', from, to)
}

function onKey(e: KeyboardEvent, index: number) {
  if (e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
    e.preventDefault()
    const to = index + (e.key === 'ArrowLeft' ? -1 : 1)
    if (to >= 0 && to < props.frames.length) emit('move', index, to)
  }
}
</script>

<template>
  <section class="space-y-2 rounded-lg border border-base-800 bg-base-900/60 p-3" :aria-label="t('capes.studio.timeline.label')" data-testid="cape-timeline">
    <div class="flex flex-wrap items-center gap-3 text-xs">
      <button
        type="button"
        class="btn-icon size-8"
        :aria-label="playing ? t('capes.studio.timeline.pause') : t('capes.studio.timeline.play')"
        :title="`${playing ? t('capes.studio.timeline.pause') : t('capes.studio.timeline.play')} (Space)`"
        :disabled="frames.length < 2"
        @click="emit('togglePlay')"
      >
        <svg v-if="!playing" viewBox="0 0 24 24" class="size-4" fill="currentColor"><path d="M7 5v14l12-7z" /></svg>
        <svg v-else viewBox="0 0 24 24" class="size-4" fill="currentColor"><path d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>
      </button>
      <label class="flex min-w-48 flex-1 items-center gap-2 text-base-400">
        <span class="shrink-0">{{ t('capes.editor.speed.label') }}</span>
        <input v-model.number="frameTime" type="range" :min="FRAME_TIME_MIN" :max="FRAME_TIME_MAX" step="10" class="min-w-0 flex-1 accent-redstone-500" :aria-label="t('capes.editor.speed.label')" />
        <span class="w-36 shrink-0 text-right tabular-nums text-base-300">{{ t('capes.editor.speed.value', { ms: frameTime, fps }) }}</span>
      </label>
      <span class="tabular-nums" :class="full ? 'text-lamp-300' : 'text-base-400'">{{ t('capes.studio.timeline.used', { used, max: CAPE_MAX_FRAMES }) }}</span>
    </div>

    <ol class="flex gap-2 overflow-x-auto pb-1" :aria-label="t('capes.studio.timeline.frames')">
      <li
        v-for="(frame, i) in frames"
        :key="frame.id"
        class="group relative shrink-0"
        draggable="true"
        @dragstart="dragFrom = i"
        @dragend="dragFrom = null; dropAt = null"
        @dragover.prevent="dropAt = i"
        @drop.prevent="onDrop(i)"
      >
        <span v-if="dropAt === i && dragFrom !== null && dragFrom !== i" class="absolute inset-y-1 -left-1.5 w-0.5 rounded bg-redstone-400" aria-hidden="true" />
        <button
          type="button"
          class="block rounded-md p-1 ring-1 transition-colors"
          :class="[i === current ? 'bg-redstone-900/40 ring-redstone-500' : 'bg-base-850 ring-base-800 hover:ring-base-600', dragFrom === i ? 'opacity-40' : '']"
          :aria-label="t('capes.studio.timeline.frame', { n: i + 1, hold: frame.hold })"
          :aria-current="i === current"
          @click="emit('select', i)"
          @keydown="onKey($event, i)"
        >
          <img :src="thumb(frame.image)" alt="" class="h-16 w-10 rounded-sm bg-[repeating-conic-gradient(#2b2833_0_25%,#35313d_0_50%)] bg-[length:8px_8px]" :class="{ '[image-rendering:pixelated]': !smooth }" draggable="false" />
        </button>
        <span class="pointer-events-none absolute top-1.5 left-1.5 rounded bg-black/65 px-1 text-[10px] font-semibold text-white tabular-nums">{{ i + 1 }}</span>
        <span v-if="frame.hold > 1" class="pointer-events-none absolute right-1.5 bottom-1.5 rounded bg-lamp-400 px-1 text-[10px] font-bold text-base-950 tabular-nums">×{{ frame.hold }}</span>
      </li>
    </ol>

    <div v-if="frames[current]" class="flex flex-wrap items-center gap-1.5 text-xs text-base-400">
      <span class="mr-1">{{ t('capes.studio.timeline.selected', { n: current + 1 }) }}</span>
      <span class="flex items-center gap-1 rounded-md bg-base-850 px-1.5 py-0.5" :title="t('capes.studio.timeline.holdHint')">
        {{ t('capes.studio.timeline.hold') }}
        <button type="button" class="btn-icon size-6" :aria-label="t('capes.studio.timeline.holdLess')" :disabled="frames[current]!.hold <= 1" @click="emit('hold', current, frames[current]!.hold - 1)">−</button>
        <span class="w-5 text-center tabular-nums text-base-100">{{ frames[current]!.hold }}</span>
        <button type="button" class="btn-icon size-6" :aria-label="t('capes.studio.timeline.holdMore')" :disabled="full" @click="emit('hold', current, frames[current]!.hold + 1)">+</button>
      </span>
      <button type="button" class="btn btn-ghost px-2 py-1 text-xs" :disabled="current === 0" @click="emit('move', current, current - 1)">{{ t('capes.studio.timeline.earlier') }}</button>
      <button type="button" class="btn btn-ghost px-2 py-1 text-xs" :disabled="current >= frames.length - 1" @click="emit('move', current, current + 1)">{{ t('capes.studio.timeline.later') }}</button>
      <button type="button" class="btn btn-ghost px-2 py-1 text-xs" :disabled="full" @click="emit('duplicate', current)">{{ t('capes.studio.timeline.duplicate') }}</button>
      <button type="button" class="btn btn-ghost px-2 py-1 text-xs text-redstone-300" :disabled="frames.length <= 1" @click="emit('remove', current)">{{ t('capes.studio.timeline.remove') }}</button>
    </div>
  </section>
</template>
