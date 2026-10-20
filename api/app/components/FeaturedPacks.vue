<script setup lang="ts">
import {
  LOADER_NAMES,
  SOURCE_NAMES,
  featuredDeepLink,
  featuredText,
  featuredTitle,
  newestVersion,
  sameOriginImage,
  type FeaturedPack,
} from '~/utils/featured'

// Empfohlene Modpacks (§37) als Karussell: ein großes Bild je Pack, wechselt alle 7 s. Pause bei Maus darüber, Fokus
// darin, verstecktem Tab, „weniger Bewegung“ oder per Knopf. Pfeile/Punkte, Pfeiltasten, Wischen auf Touch.
const props = defineProps<{ packs: FeaturedPack[] }>()
const { lang, m, fill } = useLang()
const t = computed(() => m.value.home.featured)

const INTERVAL_MS = 7000
const index = ref(0)
const count = computed(() => props.packs.length)
const current = computed(() => props.packs[Math.min(index.value, count.value - 1)] ?? null)
watch(count, (n) => {
  if (index.value >= n) index.value = 0
})

const hovered = ref(false)
const focused = ref(false)
const hidden = ref(false)
const reduced = ref(false)
const userPaused = ref(false)
const paused = computed(() => hovered.value || focused.value || hidden.value || reduced.value || userPaused.value)

function go(i: number) {
  if (!count.value) return
  index.value = (i + count.value) % count.value
  restart()
}
const next = () => go(index.value + 1)
const prev = () => go(index.value - 1)

let timer: ReturnType<typeof setInterval> | null = null
function stop() {
  if (timer) clearInterval(timer)
  timer = null
}
function restart() {
  stop()
  if (import.meta.client && !paused.value && count.value > 1) {
    timer = setInterval(() => {
      index.value = (index.value + 1) % count.value
    }, INTERVAL_MS)
  }
}
watch([paused, count], restart)

let motion: MediaQueryList | null = null
const onMotion = () => {
  reduced.value = !!motion?.matches
}
const onVisibility = () => {
  hidden.value = document.visibilityState === 'hidden'
}
onMounted(() => {
  motion = window.matchMedia('(prefers-reduced-motion: reduce)')
  onMotion()
  motion.addEventListener('change', onMotion)
  document.addEventListener('visibilitychange', onVisibility)
  onVisibility()
  restart()
})
onBeforeUnmount(() => {
  stop()
  motion?.removeEventListener('change', onMotion)
  document.removeEventListener('visibilitychange', onVisibility)
})

/** Fokus innerhalb des Karussells hält es an (Tastatur, Bildschirmleser). */
function onFocusOut(e: FocusEvent) {
  const root = e.currentTarget as HTMLElement
  if (!root.contains(e.relatedTarget as Node | null)) focused.value = false
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowRight') {
    e.preventDefault()
    next()
  } else if (e.key === 'ArrowLeft') {
    e.preventDefault()
    prev()
  }
}

// Wischen (nur Touch/Stift; die Maus nutzt die Knöpfe).
let startX: number | null = null
let startY = 0
function onPointerDown(e: PointerEvent) {
  if (e.pointerType === 'mouse') return
  startX = e.clientX
  startY = e.clientY
}
function onPointerUp(e: PointerEvent) {
  if (startX === null) return
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  startX = null
  if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(dy) * 1.5) {
    if (dx < 0) next()
    else prev()
  }
}

const fmt = computed(() => new Intl.NumberFormat(lang.value === 'de' ? 'de' : lang.value === 'es' ? 'es' : 'en', { notation: 'compact', maximumFractionDigits: 1 }))
function chips(p: FeaturedPack): string[] {
  const out = p.loaders.slice(0, 2).map((l) => LOADER_NAMES[l] ?? l)
  const v = newestVersion(p)
  if (v) out.push(v)
  return out
}
const banner = (p: FeaturedPack) => sameOriginImage(p.bannerUrl)
const icon = (p: FeaturedPack) => sameOriginImage(p.iconUrl)
</script>

<template>
  <div
    v-if="count"
    class="fp"
    role="region"
    aria-roledescription="carousel"
    :aria-label="t.title"
    @mouseenter="hovered = true"
    @mouseleave="hovered = false"
    @focusin="focused = true"
    @focusout="onFocusOut"
    @keydown="onKey"
  >
    <div class="fp-stage" data-hw-frame @pointerdown="onPointerDown" @pointerup="onPointerUp" @pointercancel="startX = null">
      <div :aria-live="paused ? 'polite' : 'off'" class="contents">
        <article
          v-for="(p, i) in packs"
          :key="p.id"
          class="fp-slide"
          :class="{ 'is-on': i === index }"
          role="group"
          aria-roledescription="slide"
          :aria-label="fill(t.slide, { n: i + 1, total: count })"
          :aria-hidden="i === index ? undefined : 'true'"
          :inert="i !== index"
        >
          <img v-if="banner(p)" :src="banner(p)!" alt="" class="fp-bg" :loading="i === 0 ? 'eager' : 'lazy'" decoding="async" />
          <img v-else-if="icon(p)" :src="icon(p)!" alt="" class="fp-bg fp-bg-blur" loading="lazy" decoding="async" />
          <div class="fp-shade" />
          <div class="fp-body">
            <div class="flex items-center gap-4">
              <img v-if="icon(p)" :src="icon(p)!" alt="" width="80" height="80" class="fp-icon" loading="lazy" decoding="async" />
              <div class="min-w-0">
                <span v-if="p.badge" class="fp-badge" :class="`fp-badge-${p.badge}`">
                  <SiteIcon :name="p.badge === 'event' ? 'moon' : 'star'" class="size-3" />{{ t.badges[p.badge] }}
                </span>
                <h3 class="fp-title display">{{ featuredTitle(p, lang) }}</h3>
                <p v-if="p.author" class="fp-author">{{ fill(t.by, { name: p.author }) }}</p>
              </div>
            </div>
            <p class="fp-text">{{ featuredText(p, lang) }}</p>
            <ul class="fp-chips">
              <li v-for="c in chips(p)" :key="c">{{ c }}</li>
              <li class="fp-dl"><SiteIcon name="download" class="size-3.5" />{{ fill(t.downloads, { n: fmt.format(p.downloads) }) }}</li>
            </ul>
            <div class="mt-6 flex flex-wrap gap-3">
              <a :href="featuredDeepLink(p)" class="btn btn-primary">
                <SiteIcon name="play" class="size-4" />{{ t.open }}
              </a>
              <a :href="p.url" target="_blank" rel="noopener noreferrer" class="btn fp-ghost">
                {{ fill(t.view, { platform: SOURCE_NAMES[p.source] }) }} <SiteIcon name="external" class="size-4" />
              </a>
            </div>
          </div>
        </article>
      </div>
    </div>

    <div v-if="count > 1" class="fp-controls">
      <button type="button" class="btn-icon" :aria-label="t.prev" :title="t.prev" @click="prev">
        <SiteIcon name="back" class="size-4" />
      </button>
      <ol class="fp-dots">
        <li v-for="(p, i) in packs" :key="p.id">
          <button
            type="button"
            class="fp-dot"
            :class="{ 'is-on': i === index }"
            :aria-label="fill(t.goTo, { title: featuredTitle(p, lang) })"
            :aria-current="i === index ? 'true' : undefined"
            @click="go(i)"
          />
        </li>
      </ol>
      <button type="button" class="btn-icon" :aria-label="t.next" :title="t.next" @click="next">
        <SiteIcon name="arrow" class="size-4" />
      </button>
      <button
        type="button"
        class="btn-icon ml-1"
        :aria-label="userPaused ? t.play : t.pause"
        :title="userPaused ? t.play : t.pause"
        :aria-pressed="userPaused"
        @click="userPaused = !userPaused"
      >
        <SiteIcon :name="userPaused ? 'play' : 'pause'" class="size-4" />
      </button>
      <span class="sr-only" aria-live="polite">{{ current ? fill(t.slide, { n: index + 1, total: count }) : '' }}</span>
    </div>
  </div>
</template>

<style scoped>
.fp-stage {
  position: relative;
  display: grid;
  min-height: 24rem;
  overflow: hidden;
  border-radius: 1rem;
  border: 1px solid var(--color-base-800);
  background: #0c0b0e;
  box-shadow: 0 30px 60px -30px rgb(0 0 0 / 0.8);
  touch-action: pan-y;
}
/* Alle Bilder liegen übereinander; nur das aktive ist sichtbar (sanftes Überblenden). */
.fp-slide {
  position: relative;
  grid-area: 1 / 1;
  display: flex;
  align-items: flex-end;
  isolation: isolate;
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.6s ease, visibility 0s linear 0.6s;
}
.fp-slide.is-on {
  opacity: 1;
  visibility: visible;
  transition: opacity 0.6s ease, visibility 0s;
}
.fp-bg {
  position: absolute;
  inset: 0;
  z-index: -2;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.04);
  transition: transform 7s linear;
}
.fp-slide.is-on .fp-bg {
  transform: scale(1);
}
.fp-bg-blur {
  filter: blur(28px) saturate(1.3);
  transform: scale(1.3);
  opacity: 0.6;
}
/* Dunkler Verlauf: links und unten fast schwarz, damit Text auf jedem Bild lesbar bleibt (auch im hellen Design). */
.fp-shade {
  position: absolute;
  inset: 0;
  z-index: -1;
  background:
    linear-gradient(90deg, rgb(8 7 10 / 0.92) 0%, rgb(8 7 10 / 0.72) 40%, rgb(8 7 10 / 0.2) 75%, rgb(8 7 10 / 0.05) 100%),
    linear-gradient(0deg, rgb(8 7 10 / 0.9) 0%, rgb(8 7 10 / 0.35) 45%, transparent 75%);
}
.fp-body {
  width: 100%;
  max-width: 38rem;
  padding: 2rem 1.25rem 1.75rem;
  color: #f4f2f7;
}
@media (min-width: 640px) {
  .fp-body {
    padding: 2.5rem 2.25rem 2.25rem;
  }
}
.fp-icon {
  width: 5rem;
  height: 5rem;
  flex-shrink: 0;
  border-radius: 1rem;
  object-fit: cover;
  background: #16141b;
  box-shadow: 0 0 0 1px rgb(255 255 255 / 0.12), 0 12px 30px -10px rgb(0 0 0 / 0.8);
}
.fp-title {
  margin-top: 0.35rem;
  font-size: clamp(1.6rem, 4vw, 2.25rem);
  line-height: 1.1;
  color: #fff;
  overflow-wrap: anywhere;
  text-shadow: 0 2px 0 rgb(0 0 0 / 0.5);
}
.fp-author {
  margin-top: 0.25rem;
  font-size: 0.85rem;
  color: rgb(244 242 247 / 0.7);
}
.fp-text {
  margin-top: 1rem;
  max-width: 34rem;
  line-height: 1.6;
  color: rgb(244 242 247 / 0.88);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.fp-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-top: 1rem;
}
.fp-chips li {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.2rem 0.6rem;
  border-radius: 999px;
  font-size: 0.75rem;
  color: #f4f2f7;
  background: rgb(255 255 255 / 0.1);
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.12);
  backdrop-filter: blur(6px);
}
.fp-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.15rem 0.55rem;
  border-radius: 999px;
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.fp-badge-recommended {
  color: #1a1408;
  background: var(--color-lamp-400);
}
.fp-badge-new {
  color: #052e1a;
  background: #4ade80;
}
.fp-badge-event {
  color: #fff;
  background: var(--color-redstone-500);
}
.fp-ghost {
  color: #f4f2f7;
  background: rgb(255 255 255 / 0.08);
  box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.18);
}
.fp-ghost:hover {
  background: rgb(255 255 255 / 0.16);
}
.fp-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  margin-top: 1rem;
}
.fp-dots {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.15rem;
}
.fp-dot {
  display: grid;
  place-items: center;
  width: 1.5rem;
  height: 1.5rem;
}
.fp-dot::before {
  content: '';
  width: 0.55rem;
  height: 0.55rem;
  border-radius: 999px;
  background: var(--color-base-600);
  transition: width 0.25s ease, background-color 0.25s ease;
}
.fp-dot:hover::before {
  background: var(--color-base-400);
}
.fp-dot.is-on::before {
  width: 1.25rem;
  background: var(--color-lamp-400);
}
.fp-dot:focus-visible {
  outline: 2px solid var(--color-lamp-400);
  outline-offset: 1px;
  border-radius: 999px;
}
/* Halloween: Mondsilber-Kante um die Bühne, Abzeichen in Violett. */
:global(html.theme-halloween) .fp-stage {
  border-color: color-mix(in srgb, var(--hw-plum) 70%, var(--color-base-800));
  box-shadow: 0 0 0 1px rgb(207 213 227 / 0.08), 0 30px 60px -30px rgb(0 0 0 / 0.9);
}
:global(html.theme-halloween) .fp-badge-event {
  background: var(--hw-violet);
}
@media (prefers-reduced-motion: reduce) {
  .fp-slide,
  .fp-slide.is-on,
  .fp-bg,
  .fp-dot::before {
    transition: none;
  }
  .fp-bg {
    transform: none;
  }
}
</style>
