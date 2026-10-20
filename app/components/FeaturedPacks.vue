<script setup lang="ts">
import type { Platform } from '~/types'
import {
  FEATURED_ROTATE_MS,
  featuredBadgeKeys,
  featuredStep,
  featuredText,
  featuredTitle,
  featuredVersion,
  swipeDirection,
  type FeaturedPack,
} from '~/utils/featured'

// Empfohlene Modpacks auf der Startseite: das TRS-Team wählt sie im Team-Bereich, die Liste kommt live von der
// TRS API (über den Kern, geprüft). Großes Karussell: Banner im Hintergrund, Icon, Titel, Text des Teams, Abzeichen,
// Loader/Version/Downloads/Autor, „Installieren“ direkt auf der Folie, sonst Klick → Projektseite. Blättert alle 7 s
// weiter – nicht bei Hover, Fokus, verstecktem Fenster oder reduzierter Bewegung. Leer oder ohne TRS-Dienste: unsichtbar.
const trs = useTrsStore()
const tasks = useTasksStore()
const curseforge = useCurseForgeStore()
const toasts = useToasts()
const router = useRouter()
const { reduced } = useMotion()

const packs = ref<FeaturedPack[]>([])
const index = ref(0)
/** Folien, deren Bilder schon geladen werden dürfen (aktuelle, Nachbarn und schon gesehene). */
const seen = ref(new Set<number>([0, 1]))
const failed = ref(new Set<string>())
const canInstall = computed(() => platformCaps.value.gameLaunch)
const current = computed(() => packs.value[index.value] ?? null)

let loadSeq = 0
async function load(force = false) {
  const seq = ++loadSeq
  try {
    const list = await backend.featuredPacks(force)
    if (seq !== loadSeq) return
    packs.value = list
  } catch {
    // Ohne Netz oder TRS-Dienste: nichts zeigen (keine Fehlermeldung auf der Startseite).
    // Beim Aktualisieren bleibt der bisherige Stand stehen.
    if (seq !== loadSeq) return
    if (!force) packs.value = []
  }
  if (index.value >= packs.value.length) index.value = 0
}
defineExpose({ refresh: () => load(true) })

onMounted(() => {
  void load()
  void curseforge.load()
})
// Einwilligung erteilt oder zurückgezogen: neu laden (ohne Dienste liefert der Kern nichts).
watch(
  () => trs.enabled,
  (on, before) => {
    if (on !== before) void load()
  },
)

function platformOf(p: FeaturedPack): Platform {
  return p.source
}
/** CurseForge fehlt in diesem Build: Folie öffnet die Seite auf CurseForge statt Projektseite/Installation. */
function external(p: FeaturedPack): boolean {
  return p.source === 'curseforge' && curseforge.available === false
}
function taskOf(p: FeaturedPack) {
  return tasks.get(modpackTaskKey(projectKey(platformOf(p), p.projectId)))
}
function title(p: FeaturedPack) {
  return featuredTitle(p, currentLocale.value)
}
function text(p: FeaturedPack) {
  return featuredText(p, currentLocale.value)
}
function imageOk(url: string | null): url is string {
  return !!url && !failed.value.has(url)
}
function onImageError(url: string | null) {
  if (url) failed.value = new Set(failed.value).add(url)
}

function openDetails(p: FeaturedPack) {
  if (swiped) return
  if (external(p)) {
    void backend.openExternalUrl(p.url).catch((e) => toasts.error(e))
    return
  }
  void router.push(projectRoute(platformOf(p), p.projectId))
}
function install(p: FeaturedPack) {
  requestModpackInstall({ projectId: p.projectId, title: p.title, iconUrl: p.iconUrl }, platformOf(p))
}

// --- Blättern -------------------------------------------------------------------------------

function go(i: number) {
  if (!packs.value.length) return
  index.value = featuredStep(i, 0, packs.value.length)
}
function step(delta: number) {
  go(featuredStep(index.value, delta, packs.value.length))
}
watch(index, (i) => {
  const n = packs.value.length
  if (!n) return
  const next = new Set(seen.value)
  for (const j of [i, featuredStep(i, 1, n), featuredStep(i, -1, n)]) next.add(j)
  seen.value = next
})

const hovering = ref(false)
const focused = ref(false)
const visible = ref(typeof document === 'undefined' || document.visibilityState !== 'hidden')
const paused = computed(() => hovering.value || focused.value || !visible.value || reduced.value || packs.value.length < 2)
let timer: ReturnType<typeof setTimeout> | undefined
watch(
  [index, paused, () => packs.value.length],
  () => {
    clearTimeout(timer)
    if (!paused.value) timer = setTimeout(() => step(1), FEATURED_ROTATE_MS)
  },
  { immediate: true },
)
function onVisibility() {
  visible.value = document.visibilityState !== 'hidden'
}
onMounted(() => document.addEventListener('visibilitychange', onVisibility))
onBeforeUnmount(() => {
  clearTimeout(timer)
  document.removeEventListener('visibilitychange', onVisibility)
})

const root = ref<HTMLElement | null>(null)
function onFocusOut(e: FocusEvent) {
  const next = e.relatedTarget as Node | null
  if (!next || !root.value?.contains(next)) focused.value = false
}
function onKey(e: KeyboardEvent) {
  if (e.altKey || e.ctrlKey || e.metaKey || packs.value.length < 2) return
  if ((e.target as HTMLElement | null)?.closest('input, textarea, select')) return
  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    e.preventDefault()
    step(e.key === 'ArrowLeft' ? -1 : 1)
  }
}

// Wischen (Touch/Stift): waagerecht genug → blättern; der Klick danach öffnet nichts.
let start: { x: number; y: number } | null = null
let swiped = false
function onPointerDown(e: PointerEvent) {
  swiped = false
  start = e.pointerType === 'mouse' ? null : { x: e.clientX, y: e.clientY }
}
function onPointerUp(e: PointerEvent) {
  if (!start) return
  const dir = swipeDirection(e.clientX - start.x, e.clientY - start.y)
  start = null
  if (dir && packs.value.length > 1) {
    swiped = true
    step(dir)
    // Der Klick folgt direkt auf pointerup – danach wieder normal.
    setTimeout(() => (swiped = false), 350)
  }
}
</script>

<template>
  <section
    v-if="current"
    ref="root"
    class="featured"
    aria-labelledby="featured-heading"
    aria-roledescription="carousel"
    data-testid="featured-packs"
    @mouseenter="hovering = true"
    @mouseleave="hovering = false"
    @focusin="focused = true"
    @focusout="onFocusOut"
    @keydown="onKey"
  >
    <div class="mb-3 flex items-end justify-between gap-4 mobile:items-center">
      <div class="min-w-0">
        <h2 id="featured-heading" class="heading">{{ t('featured.title') }}</h2>
        <p class="mt-0.5 text-xs text-base-400">{{ t('featured.hint') }}</p>
      </div>
      <NuxtLink :to="{ path: '/browse', query: { kind: 'modpack' } }" class="shrink-0 text-xs text-base-400 hover:text-base-50 mobile:-my-3 mobile:py-3">
        {{ t('featured.more') }}
      </NuxtLink>
    </div>

    <div class="stage card relative overflow-hidden" @pointerdown="onPointerDown" @pointerup="onPointerUp" @pointercancel="start = null">
      <div
        v-for="(p, i) in packs"
        :key="p.id"
        class="slide"
        :class="{ 'slide-on': i === index }"
        role="group"
        aria-roledescription="slide"
        :aria-label="t('featured.slideOf', { n: i + 1, total: packs.length })"
        :aria-hidden="i === index ? undefined : 'true'"
        :inert="i !== index"
        :data-source="p.source"
      >
        <!-- Hintergrund: Banner, sonst das Icon groß und weich, sonst ein Verlauf im Akzent. -->
        <div class="bg" aria-hidden="true">
          <template v-if="seen.has(i)">
            <img v-if="imageOk(p.bannerUrl)" :src="p.bannerUrl" alt="" loading="lazy" decoding="async" class="bg-img" draggable="false" @error="onImageError(p.bannerUrl)" />
            <img v-else-if="imageOk(p.iconUrl)" :src="p.iconUrl" alt="" loading="lazy" decoding="async" class="bg-img bg-blur" draggable="false" />
          </template>
          <div class="shade" />
        </div>

        <!-- Ganze Folie klickbar → Details (die Knöpfe liegen darüber und tun ihr Eigenes). -->
        <button type="button" class="hit" tabindex="-1" :aria-label="t('featured.openNamed', { title: title(p) })" @click="openDetails(p)" />

        <div class="content">
          <span class="icon" aria-hidden="true">
            <img v-if="seen.has(i) && imageOk(p.iconUrl)" :src="p.iconUrl" alt="" loading="lazy" decoding="async" class="size-full object-cover" draggable="false" @error="onImageError(p.iconUrl)" />
            <PixelIdenticon v-else :seed="p.title" color="var(--color-base-400)" />
          </span>

          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-1.5">
              <span v-if="p.badge" class="badge featured-badge" :class="`featured-badge-${p.badge}`">{{ t(featuredBadgeKeys[p.badge]) }}</span>
              <span class="badge bg-black/45 text-base-50 backdrop-blur">
                <svg viewBox="0 0 24 24" class="size-3 shrink-0" aria-hidden="true"><path :fill="platformMarks[p.source].color" :d="platformMarks[p.source].d" /></svg>
                {{ t(`browse.source.${p.source}`) }}
              </span>
            </div>
            <h3 class="title display mt-2 truncate text-3xl leading-tight text-base-50 mobile:text-2xl">{{ title(p) }}</h3>
            <p class="mt-1 line-clamp-2 max-w-2xl text-sm text-base-200 mobile:text-xs">{{ text(p) }}</p>
            <p class="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-base-200">
              <span v-if="p.loaders.length || featuredVersion(p)" class="flex items-center gap-1.5">
                <span v-for="l in p.loaders.slice(0, 3)" :key="l" class="chip bg-black/45 px-2 py-0.5 text-base-50">{{ loaderLabels[l as keyof typeof loaderLabels] ?? l }}</span>
                <span v-if="featuredVersion(p)" class="chip bg-black/45 px-2 py-0.5 font-mono text-base-50">{{ featuredVersion(p) }}</span>
              </span>
              <span v-if="p.downloads" class="flex items-center gap-1">
                <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icons.install" /></svg>
                {{ t('featured.downloads', { count: formatCount(p.downloads) }) }}
              </span>
              <span v-if="p.author" class="truncate mobile:hidden">{{ t('featured.by', { author: p.author }) }}</span>
            </p>
          </div>

          <div class="actions">
            <button v-if="external(p)" type="button" class="btn btn-primary" @click="openDetails(p)">
              {{ t('curseforge.openOnCurseForge') }}
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icons.external" /></svg>
            </button>
            <template v-else>
              <template v-if="canInstall">
                <button v-if="taskOf(p)?.status === 'running'" type="button" class="btn btn-primary tabular-nums" disabled>
                  {{ t(taskOf(p)!.paused ? 'project.pack.pausedPercent' : 'project.pack.installingPercent', { percent: taskOf(p)!.percent ?? 0 }) }}
                </button>
                <button
                  v-else-if="taskOf(p)?.status === 'done' && taskOf(p)!.instanceId"
                  type="button"
                  class="btn btn-primary"
                  @click="router.push(`/instances/${taskOf(p)!.instanceId}`)"
                >
                  {{ t('project.pack.openInstance') }}
                </button>
                <button v-else type="button" class="btn btn-primary" :aria-label="t('featured.installNamed', { title: title(p) })" @click="install(p)">
                  <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icons.install" /></svg>
                  {{ t('featured.install') }}
                </button>
              </template>
              <button type="button" class="btn btn-ghost bg-base-900/70 backdrop-blur" @click="openDetails(p)">{{ t('featured.details') }}</button>
            </template>
          </div>
        </div>
      </div>

      <template v-if="packs.length > 1">
        <button type="button" class="nav nav-prev" :title="t('featured.previous')" :aria-label="t('featured.previous')" @click="step(-1)">
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
        </button>
        <button type="button" class="nav nav-next" :title="t('featured.next')" :aria-label="t('featured.next')" @click="step(1)">
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
        </button>
      </template>
    </div>

    <div v-if="packs.length > 1" class="mt-2.5 flex items-center justify-center gap-1.5">
      <button
        v-for="(p, i) in packs"
        :key="p.id"
        type="button"
        class="dot"
        :class="{ 'dot-on': i === index, 'dot-run': i === index && !paused }"
        :aria-label="t('featured.goTo', { title: title(p), n: i + 1, total: packs.length })"
        :aria-current="i === index ? 'true' : undefined"
        :style="{ '--rotate-ms': `${FEATURED_ROTATE_MS}ms` }"
        @click="go(i)"
      >
        <span :key="`${index}-${paused}`" class="dot-fill" />
      </button>
    </div>
    <p class="sr-only" :aria-live="paused ? 'polite' : 'off'">{{ t('featured.slideOf', { n: index + 1, total: packs.length }) }}: {{ title(current) }}</p>
  </section>
</template>

<style scoped>
@reference "~/assets/css/main.css";

/* Feste Höhe: kein Springen beim Blättern oder Nachladen der Bilder. */
.stage {
  height: 15rem;
  padding: 0;
}
.slide {
  position: absolute;
  inset: 0;
  opacity: 0;
  visibility: hidden;
  transition:
    opacity 0.45s cubic-bezier(0.2, 0.8, 0.2, 1),
    visibility 0s linear 0.45s;
}
.slide-on {
  opacity: 1;
  visibility: visible;
  transition:
    opacity 0.45s cubic-bezier(0.2, 0.8, 0.2, 1),
    visibility 0s;
}
.bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(120% 140% at 85% 20%, color-mix(in srgb, var(--color-redstone-500) 38%, transparent) 0%, transparent 60%),
    linear-gradient(120deg, var(--color-base-850) 0%, var(--color-base-900) 100%);
}
.bg-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.02);
  transition: transform 6s ease-out;
}
.slide-on .bg-img {
  transform: scale(1.06);
}
.bg-blur {
  filter: blur(28px) saturate(1.3);
  opacity: 0.55;
  transform: scale(1.4);
}
.slide-on .bg-blur {
  transform: scale(1.45);
}
/* Schleier für lesbaren Text: links und unten dicht, rechts oben offen. */
.shade {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--color-base-950) 92%, transparent) 0%, color-mix(in srgb, var(--color-base-950) 70%, transparent) 38%, color-mix(in srgb, var(--color-base-950) 20%, transparent) 75%, transparent 100%),
    linear-gradient(0deg, color-mix(in srgb, var(--color-base-950) 80%, transparent) 0%, transparent 60%);
}
.hit {
  position: absolute;
  inset: 0;
  cursor: pointer;
}
.content {
  @apply pointer-events-none relative flex h-full items-end gap-5 px-6 pt-5 pb-6;
}
.content :is(button, a) {
  pointer-events: auto;
}
.icon {
  @apply block size-20 shrink-0 overflow-hidden rounded-xl bg-base-800;
  box-shadow:
    0 0 0 2px color-mix(in srgb, var(--color-base-50) 12%, transparent),
    0 14px 30px -12px rgb(0 0 0 / 0.7);
}
.title {
  text-shadow: 0 2px 0 color-mix(in srgb, var(--color-base-950) 80%, transparent);
}
.actions {
  @apply flex shrink-0 items-center gap-2;
}
.featured-badge {
  @apply text-base-950;
}
.featured-badge-recommended {
  @apply bg-lamp-400;
}
.featured-badge-new {
  @apply bg-ok;
}
.featured-badge-event {
  @apply bg-redstone-400 text-white;
}

.nav {
  @apply absolute top-1/2 grid size-10 -translate-y-1/2 place-items-center rounded-full bg-base-950/70 text-base-50 opacity-0 backdrop-blur transition-opacity hover:bg-base-900 hover:text-base-50 focus-visible:opacity-100;
}
.stage:hover .nav {
  opacity: 1;
}
.nav-prev {
  left: 0.75rem;
}
.nav-next {
  right: 0.75rem;
}

.dot {
  @apply relative h-1.5 w-4 overflow-hidden rounded-full bg-base-700 transition-[width,background-color] hover:bg-base-400;
}
.dot-on {
  @apply w-8 bg-base-600;
}
.dot-fill {
  @apply absolute inset-0 rounded-full bg-redstone-400;
  transform-origin: left center;
  transform: scaleX(0);
}
.dot-on .dot-fill {
  transform: scaleX(1);
}
.dot-run .dot-fill {
  animation: featured-fill var(--rotate-ms) linear forwards;
}
@keyframes featured-fill {
  from {
    transform: scaleX(0);
  }
  to {
    transform: scaleX(1);
  }
}

/* Handy: kompakter, Inhalt untereinander, Pfeile weg (Wischen + Punkte). */
:root.is-mobile .stage {
  height: 18rem;
}
:root.is-mobile .content {
  @apply flex-col items-start justify-end gap-3 px-4 pt-4 pb-4;
}
:root.is-mobile .icon {
  @apply size-12;
}
:root.is-mobile .actions {
  @apply w-full;
}
:root.is-mobile .actions > * {
  @apply flex-1;
}
:root.is-mobile .nav {
  display: none;
}
@media (max-width: 760px) {
  .content {
    @apply flex-wrap;
  }
}
</style>
