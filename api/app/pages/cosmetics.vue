<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'

// Kosmetik: oben die TRS-Umhänge (wie früher /capes), darunter Kopf-Kosmetik im Format v2 mit 3D-Vorschau
// (derselbe Renderer wie Studio-Werkbank und Launcher). /capes leitet per 301 hierher (server/middleware).
const { lang, m, fill } = useLang()
const [{ data, error }, { data: hatData, error: hatError }] = await Promise.all([useCapes(), useHats()])
const capes = computed(() => data.value?.capes ?? [])
const hats = computed(() => hatData.value?.hats ?? [])
const companions = computed(() => hatData.value?.companions ?? [])
const selected = ref<SiteCape | null>(null)
watchEffect(() => {
  if (!selected.value && capes.value.length) selected.value = capes.value[0]!
})

const siteUrl = useSiteUrl()
usePageSeo(() => ({
  path: '/cosmetics',
  title: m.value.seo.cosmetics.title,
  description: m.value.seo.cosmetics.description,
  image: { url: '/shots/cape-physics.png', width: 854, height: 480, alt: m.value.seo.cosmetics.title },
  jsonLd: [
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: m.value.cosmetics.title, path: '/cosmetics' },
    ]),
  ],
}))

type Unlockable = { unlock: 'free' | 'code' | 'admin' | 'event', achievement?: { en: string, de: string, es: string } | null }
const unlockLabel = (c: Unlockable) =>
  c.unlock === 'event' ? m.value.cosmetics.halloween : c.achievement ? fill(m.value.capes.achievement, { name: c.achievement[lang.value] ?? c.achievement.en }) : c.unlock === 'free' ? m.value.capes.free : c.unlock === 'code' ? m.value.capes.code : m.value.capes.admin
const unlockClass = (c: Unlockable) =>
  c.unlock === 'event' ? 'badge-hw' : c.achievement ? 'bg-[#2a1f4a] text-[#c4a5ff]' : c.unlock === 'free' ? 'bg-ok/15 text-ok' : c.unlock === 'code' ? 'bg-lamp-900 text-lamp-300' : 'bg-redstone-900 text-redstone-300'

// Kopf-Kosmetik: Klick wählt aus, Überfahren (nur mit Maus) zeigt kurz in der Vorschau.
const hatId = ref<string | null>(null)
const hoverId = ref<string | null>(null)
const selectedHat = computed(() => hats.value.find((h) => h.id === hatId.value) ?? hats.value[0] ?? null)
const shownHat = computed(() => hats.value.find((h) => h.id === hoverId.value) ?? selectedHat.value)
const night = ref(false)
const animate = ref(true)
const hatLoading = ref(false)
const compId = ref<string | null>(null)
const compHover = ref<string | null>(null)
const selectedComp = computed(() => companions.value.find((h) => h.id === compId.value) ?? companions.value[0] ?? null)
const shownComp = computed(() => companions.value.find((h) => h.id === compHover.value) ?? selectedComp.value)
const compLoading = ref(false)
let canHover = false
let hoverTimer: ReturnType<typeof setTimeout> | null = null
let compHoverTimer: ReturnType<typeof setTimeout> | null = null
onMounted(() => {
  canHover = window.matchMedia('(hover: hover) and (pointer: fine)').matches
  // Weniger Bewegung gewünscht → Animation startet aus (Schalter bleibt da).
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) animate.value = false
})
onBeforeUnmount(() => {
  if (hoverTimer) clearTimeout(hoverTimer)
  if (compHoverTimer) clearTimeout(compHoverTimer)
})
function hoverHat(id: string | null) {
  if (!canHover) return
  if (hoverTimer) clearTimeout(hoverTimer)
  // Kurz warten: beim schnellen Überstreichen nicht jedes Modell anzeigen.
  hoverTimer = setTimeout(() => (hoverId.value = id), id ? 140 : 60)
}
function pickHat(h: SiteHat) {
  hatId.value = h.id
  hoverId.value = null
}

function hoverComp(id: string | null) {
  if (!canHover) return
  if (compHoverTimer) clearTimeout(compHoverTimer)
  compHoverTimer = setTimeout(() => (compHover.value = id), id ? 140 : 60)
}
function pickComp(h: SiteHat) {
  compId.value = h.id
  compHover.value = null
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-2xl">
      <h1 class="display text-5xl leading-tight text-base-50">{{ m.cosmetics.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ m.cosmetics.lead }}</p>
    </header>

    <!-- Umhänge -->
    <section id="capes" class="mt-12 scroll-mt-24" aria-labelledby="capes-title">
      <h2 id="capes-title" class="display text-3xl text-base-50">{{ m.cosmetics.capesTitle }}</h2>
      <p class="mt-2 max-w-2xl text-base-400">{{ m.capes.lead }}</p>

      <p v-if="error" class="mt-8 text-lamp-300">{{ m.common.error }}</p>
      <p v-else-if="!capes.length" class="mt-8 text-base-400">{{ m.capes.empty }}</p>
      <div v-else class="mt-8 grid gap-6 lg:grid-cols-[1fr_22rem]">
        <ul class="grid grid-cols-2 content-start gap-3 sm:grid-cols-3 md:grid-cols-4">
          <li v-for="c in capes" :key="c.id">
            <button
              type="button"
              class="cos-card card"
              :class="{ 'cos-card-on': selected?.id === c.id }"
              :aria-pressed="selected?.id === c.id"
              @click="selected = c"
            >
              <span class="grid h-32 place-items-center">
                <ClientOnly>
                  <CapeThumb :texture="c.url" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="60" />
                  <template #fallback><span class="block h-24 w-15 rounded-sm bg-base-800" /></template>
                </ClientOnly>
              </span>
              <span class="block truncate text-sm font-semibold text-base-50">{{ c.name }}</span>
              <span class="mt-1.5 flex flex-wrap justify-center gap-1">
                <span class="badge" :class="unlockClass(c)">{{ unlockLabel(c) }}</span>
                <span v-if="c.scale > 1" class="badge bg-base-800 text-base-200">HD</span>
                <span v-if="c.frames > 1" class="badge bg-lamp-900 text-lamp-300">{{ m.capes.animated }}</span>
              </span>
            </button>
          </li>
        </ul>

        <aside class="lg:sticky lg:top-24 lg:self-start">
          <div class="card overflow-hidden">
            <div class="stage relative">
              <ClientOnly><CapeViewer :cape="selected" :height="400" /></ClientOnly>
              <p class="absolute top-3 left-4 text-xs tracking-[0.18em] text-base-400 uppercase">{{ m.capes.preview }}</p>
              <p class="absolute right-4 bottom-3 text-xs text-base-600">{{ m.capes.dragHint }}</p>
            </div>
            <div v-if="selected" class="border-t border-base-800 p-5">
              <p class="display text-2xl text-base-50">{{ selected.name }}</p>
              <span class="badge mt-2" :class="unlockClass(selected)">{{ unlockLabel(selected) }}</span>
            </div>
          </div>
          <div class="mt-5 p-1">
            <h3 class="font-semibold text-base-50">{{ m.capes.howTitle }}</h3>
            <p class="mt-1.5 text-sm text-base-400">{{ m.capes.howText }}</p>
          </div>
        </aside>
      </div>
    </section>

    <!-- Trennlinie: Redstone-Leitung mit Lampe in der Mitte -->
    <div class="divider my-16" role="separator" aria-hidden="true"><span /></div>

    <!-- Kopf-Kosmetik -->
    <section id="hats" class="scroll-mt-24" aria-labelledby="hats-title">
      <h2 id="hats-title" class="display text-3xl text-base-50">{{ m.cosmetics.hatsTitle }}</h2>
      <p class="mt-2 max-w-2xl text-base-400">{{ m.cosmetics.hatsLead }}</p>

      <p v-if="hatError" class="mt-8 text-lamp-300">{{ m.common.error }}</p>
      <p v-else-if="!hats.length" class="mt-8 text-base-400">{{ m.cosmetics.hatsEmpty }}</p>
      <div v-else class="mt-8 grid gap-6 lg:grid-cols-[1fr_22rem]">
        <ul class="grid grid-cols-2 content-start gap-3 sm:grid-cols-3" @pointerleave="hoverHat(null)">
          <li v-for="h in hats" :key="h.id">
            <button
              type="button"
              class="cos-card card hat-card"
              :class="{ 'cos-card-on': selectedHat?.id === h.id }"
              :aria-pressed="selectedHat?.id === h.id"
              :aria-label="fill(m.cosmetics.choose, { name: h.name })"
              @click="pickHat(h)"
              @pointerenter="hoverHat(h.id)"
            >
              <span class="hat-img" :class="{ 'hat-img-night': night }">
                <img :src="h.card" alt="" width="512" height="512" loading="lazy" decoding="async" class="hat-day" />
                <img :src="h.cardNight" alt="" width="512" height="512" loading="lazy" decoding="async" class="hat-night" />
              </span>
              <span class="mt-3 block truncate text-sm font-semibold text-base-50">{{ h.name }}</span>
              <span class="mt-1.5 flex flex-wrap justify-center gap-1">
                <span class="badge" :class="unlockClass(h)">{{ unlockLabel(h) }}</span>
                <span v-if="h.glowFrames > 0" class="badge bg-redstone-900 text-redstone-300">{{ m.cosmetics.glowing }}</span>
                <span v-if="h.animated" class="badge bg-lamp-900 text-lamp-300">{{ m.cosmetics.animated }}</span>
              </span>
            </button>
          </li>
        </ul>

        <aside class="lg:sticky lg:top-24 lg:self-start">
          <div class="card overflow-hidden">
            <div class="stage relative" :class="{ 'stage-night': night }">
              <ClientOnly><HatViewer :hat="shownHat" :night="night" :animate="animate" :height="400" @loading="hatLoading = $event" /></ClientOnly>
              <p class="absolute top-3 left-4 text-xs tracking-[0.18em] text-base-400 uppercase">{{ m.capes.preview }}</p>
              <p class="absolute right-4 bottom-3 text-xs text-base-600">{{ m.capes.dragHint }}</p>
              <span v-if="hatLoading" class="spinner absolute top-3 right-4" aria-hidden="true" />
            </div>
            <div class="flex flex-wrap items-center gap-2 border-t border-base-800 px-5 py-3">
              <div class="seg" role="group" :aria-label="`${m.cosmetics.day} / ${m.cosmetics.night}`">
                <button type="button" :aria-pressed="!night" @click="night = false"><SiteIcon name="sun" class="size-3.5" />{{ m.cosmetics.day }}</button>
                <button type="button" :aria-pressed="night" @click="night = true"><SiteIcon name="moon" class="size-3.5" />{{ m.cosmetics.night }}</button>
              </div>
              <button type="button" class="tog" :aria-pressed="animate" @click="animate = !animate">
                <SiteIcon :name="animate ? 'pause' : 'play'" class="size-3.5" />{{ m.cosmetics.animation }}
              </button>
            </div>
            <div v-if="shownHat" class="border-t border-base-800 p-5">
              <p class="display text-2xl text-base-50">{{ shownHat.name }}</p>
              <span class="badge mt-2" :class="unlockClass(shownHat)">{{ unlockLabel(shownHat) }}</span>
            </div>
          </div>
          <div class="mt-5 p-1">
            <h3 class="font-semibold text-base-50">{{ m.cosmetics.howTitle }}</h3>
            <p class="mt-1.5 text-sm text-base-400">{{ m.cosmetics.howText }}</p>
          </div>
        </aside>
      </div>
    </section>

    <div class="divider my-16" role="separator" aria-hidden="true"><span /></div>

    <!-- Begleiter (slot companion), zusätzlich zum Hut -->
    <section id="companions" class="scroll-mt-24 pb-16" aria-labelledby="companions-title">
      <h2 id="companions-title" class="display text-3xl text-base-50">{{ m.cosmetics.companionsTitle }}</h2>
      <p class="mt-2 max-w-2xl text-base-400">{{ m.cosmetics.companionsLead }}</p>

      <p v-if="hatError" class="mt-8 text-lamp-300">{{ m.common.error }}</p>
      <p v-else-if="!companions.length" class="mt-8 text-base-400">{{ m.cosmetics.companionsEmpty }}</p>
      <div v-else class="mt-8 grid gap-6 lg:grid-cols-[1fr_22rem]">
        <ul class="grid grid-cols-2 content-start gap-3 sm:grid-cols-3" @pointerleave="hoverComp(null)">
          <li v-for="h in companions" :key="h.id">
            <button
              type="button"
              class="cos-card card hat-card"
              :class="{ 'cos-card-on': selectedComp?.id === h.id }"
              :aria-pressed="selectedComp?.id === h.id"
              :aria-label="fill(m.cosmetics.choose, { name: h.name })"
              @click="pickComp(h)"
              @pointerenter="hoverComp(h.id)"
            >
              <span class="hat-img" :class="{ 'hat-img-night': night }">
                <img :src="h.card" alt="" width="512" height="512" loading="lazy" decoding="async" class="hat-day" />
                <img :src="h.cardNight" alt="" width="512" height="512" loading="lazy" decoding="async" class="hat-night" />
              </span>
              <span class="mt-3 block truncate text-sm font-semibold text-base-50">{{ h.name }}</span>
              <span class="mt-1.5 flex flex-wrap justify-center gap-1">
                <span class="badge" :class="unlockClass(h)">{{ unlockLabel(h) }}</span>
                <span v-if="h.glowFrames > 0" class="badge bg-redstone-900 text-redstone-300">{{ m.cosmetics.glowing }}</span>
                <span v-if="h.animated" class="badge bg-lamp-900 text-lamp-300">{{ m.cosmetics.animated }}</span>
              </span>
            </button>
          </li>
        </ul>

        <aside class="lg:sticky lg:top-24 lg:self-start">
          <div class="card overflow-hidden">
            <div class="stage relative" :class="{ 'stage-night': night }">
              <ClientOnly><HatViewer :hat="shownComp" :night="night" :animate="animate" :height="400" @loading="compLoading = $event" /></ClientOnly>
              <p class="absolute top-3 left-4 text-xs tracking-[0.18em] text-base-400 uppercase">{{ m.capes.preview }}</p>
              <p class="absolute right-4 bottom-3 text-xs text-base-600">{{ m.capes.dragHint }}</p>
              <span v-if="compLoading" class="spinner absolute top-3 right-4" aria-hidden="true" />
            </div>
            <div class="flex flex-wrap items-center gap-2 border-t border-base-800 px-5 py-3">
              <div class="seg" role="group" :aria-label="`${m.cosmetics.day} / ${m.cosmetics.night}`">
                <button type="button" :aria-pressed="!night" @click="night = false"><SiteIcon name="sun" class="size-3.5" />{{ m.cosmetics.day }}</button>
                <button type="button" :aria-pressed="night" @click="night = true"><SiteIcon name="moon" class="size-3.5" />{{ m.cosmetics.night }}</button>
              </div>
              <button type="button" class="tog" :aria-pressed="animate" @click="animate = !animate">
                <SiteIcon :name="animate ? 'pause' : 'play'" class="size-3.5" />{{ m.cosmetics.animation }}
              </button>
            </div>
            <div v-if="shownComp" class="border-t border-base-800 p-5">
              <p class="display text-2xl text-base-50">{{ shownComp.name }}</p>
              <span class="badge mt-2" :class="unlockClass(shownComp)">{{ unlockLabel(shownComp) }}</span>
            </div>
          </div>
          <div class="mt-5 p-1">
            <h3 class="font-semibold text-base-50">{{ m.cosmetics.companionsHowTitle }}</h3>
            <p class="mt-1.5 text-sm text-base-400">{{ m.cosmetics.companionsHowText }}</p>
          </div>
        </aside>
      </div>
    </section>
  </div>
</template>

<style scoped>
.badge-hw {
  background: #2a1840;
  color: #ffb06a;
}
.cos-card {
  display: block;
  width: 100%;
  padding: 0.75rem 0.75rem 1rem;
  text-align: center;
  transition: transform 0.15s, border-color 0.15s, box-shadow 0.15s;
}
.cos-card:hover {
  transform: translateY(-2px);
  border-color: var(--color-base-700);
}
.cos-card-on {
  border-color: var(--color-lamp-400);
  box-shadow: 0 0 22px -8px var(--color-lamp-400);
}
.hat-card {
  padding: 0.5rem 0.5rem 1rem;
}
.hat-img {
  position: relative;
  display: block;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: 0.5rem;
  background: var(--color-base-950);
}
.hat-img img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: opacity 0.25s;
}
.hat-img .hat-night,
.hat-img-night .hat-day {
  opacity: 0;
}
.hat-img-night .hat-night {
  opacity: 1;
}
.stage {
  background-color: var(--color-base-950);
  background-image: var(--deepslate);
  background-size: 48px 48px;
  transition: background-color 0.3s;
}
.stage-night {
  background-color: #050508;
  background-image: none;
}
.divider {
  position: relative;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--color-redstone-900) 12%, var(--color-redstone-600) 50%, var(--color-redstone-900) 88%, transparent);
}
.divider span {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 14px;
  height: 14px;
  transform: translate(-50%, -50%) rotate(45deg);
  background: var(--color-lamp-400);
  box-shadow: 0 0 18px 2px var(--color-lamp-400);
  border: 2px solid var(--color-base-950);
}
.seg {
  display: inline-flex;
  overflow: hidden;
  border: 1px solid var(--color-base-700);
  border-radius: 0.5rem;
}
.seg button,
.tog {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.35rem 0.7rem;
  font-size: 0.8rem;
  color: var(--color-base-400);
  transition: background-color 0.15s, color 0.15s;
}
.seg button[aria-pressed='true'] {
  background: var(--color-base-800);
  color: var(--color-base-50);
}
.tog {
  border: 1px solid var(--color-base-700);
  border-radius: 0.5rem;
}
.tog[aria-pressed='true'] {
  color: var(--color-lamp-300);
  border-color: var(--color-lamp-400);
}
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--color-base-700);
  border-top-color: var(--color-lamp-400);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}
</style>
