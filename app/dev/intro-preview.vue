<script setup lang="ts">
// Nur in `nuxt dev` (siehe nuxt.config.ts): Vorschau der Start-Animation im normalen Browser, ohne Tauri.
// /intro-preview?design=redstone|halloween|winter&length=short|medium|long&sound=1
import { INTRO_DESIGNS, introDesignById } from '~/utils/intro/designs'
import { IntroPlayer } from '~/utils/intro/player'
import { INTRO_LENGTHS, type IntroLength } from '~/utils/intro/timeline'

definePageMeta({ layout: false })

const route = useRoute()
const router = useRouter()
const cv = useTemplateRef<HTMLCanvasElement>('cv')
const design = ref(introDesignById(String(route.query.design ?? '')).id)
const length = ref<IntroLength>((INTRO_LENGTHS as readonly string[]).includes(String(route.query.length)) ? (route.query.length as IntroLength) : 'long')
const sound = ref(route.query.sound === '1')
const reduced = ref(route.query.reduced === '1')
const calm = ref(route.query.calm === '1')
const gone = ref(false)
const time = ref(0)
let player: IntroPlayer | null = null
let tick = 0

function play() {
  if (!cv.value) return
  player?.stop(0.1)
  gone.value = false
  const d = introDesignById(design.value)
  player = new IntroPlayer({
    canvas: cv.value,
    design: d,
    length: length.value,
    reduced: reduced.value,
    calm: calm.value,
    sound: sound.value,
    volume: 0.85,
    jingle: introJingleUrl(d.id),
    logo: '/icon.png',
    onReady: () => {
      gone.value = true
      const cur = player
      setTimeout(() => cur?.stop(0.2), 500)
    },
  })
  player.start()
  void router.replace({ query: { design: design.value, length: length.value, sound: sound.value ? '1' : '0', reduced: reduced.value ? '1' : undefined, calm: calm.value ? '1' : undefined } })
}

function skip() {
  if (gone.value || !player) return
  player.skip()
}

onMounted(() => {
  play()
  tick = window.setInterval(() => (time.value = player?.time ?? 0), 100)
  ;(window as { __intro?: unknown }).__intro = { play, still: (t: number) => player?.still(t) }
})
onBeforeUnmount(() => {
  clearInterval(tick)
  player?.stop(0.05)
})
</script>

<template>
  <div class="fixed inset-0 grid place-items-center bg-base-950 text-base-100">
    <div class="text-center">
      <h1 class="display text-3xl">TRS LAUNCHER</h1>
      <p class="opacity-60">(hier übernimmt die App nach der Animation)</p>
    </div>
    <div class="stage" :class="{ gone }" @pointerdown="skip">
      <canvas ref="cv" class="block size-full" style="image-rendering: pixelated" />
    </div>
    <div class="bar">
      <button v-for="d in INTRO_DESIGNS" :key="d.id" :class="{ on: design === d.id }" @click="(design = d.id), play()">{{ d.id }}</button>
      <span class="sep" />
      <button v-for="l in INTRO_LENGTHS" :key="l" :class="{ on: length === l }" @click="(length = l), play()">{{ l }}</button>
      <span class="sep" />
      <button @click="play()">↻ Nochmal</button>
      <button :class="{ on: sound }" @click="(sound = !sound), play()">Ton</button>
      <button :class="{ on: calm }" @click="(calm = !calm), play()">Ohne Flackern</button>
      <button :class="{ on: reduced }" @click="(reduced = !reduced), play()">Reduziert</button>
      <span class="tabular-nums opacity-70">{{ time.toFixed(2) }} s</span>
    </div>
  </div>
</template>

<style scoped>
.stage {
  position: fixed;
  inset: 0;
  transition: opacity 0.45s ease;
  cursor: pointer;
}
.stage.gone {
  opacity: 0;
  pointer-events: none;
}
.bar {
  position: fixed;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 8px 10px;
  border-radius: 12px;
  background: rgb(12 12 16 / 0.75);
  border: 1px solid rgb(255 255 255 / 0.08);
  z-index: 5;
}
.bar button {
  padding: 6px 10px;
  border-radius: 8px;
  background: rgb(255 255 255 / 0.06);
  border: 1px solid rgb(255 255 255 / 0.1);
}
.bar button.on {
  background: #e0281e;
  color: #fff;
}
.sep {
  width: 1px;
  height: 22px;
  background: rgb(255 255 255 / 0.12);
}
</style>
