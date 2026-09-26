<script setup lang="ts">
// Screenshot-Galerie eines Update-Beitrags (wie im Launcher): großes Bild mit Bildunterschrift, Pfeile,
// Vorschaubilder; Klick aufs Bild → Vollbild. Tasten ←/→ (Pos1/Ende) blättern, sobald die Galerie den Fokus
// hat oder das Vollbild offen ist; Esc schließt das Vollbild. Bilder nur aus dem Repo (raw.githubusercontent.com).
import type { PostShot } from '~/utils/changelog'

const props = withDefaults(defineProps<{ shots: PostShot[], accent?: string | null }>(), { accent: null })
const { m, fill } = useLang()

const index = ref(0)
const zoomed = ref(false)
const closeButton = ref<HTMLButtonElement | null>(null)
const current = computed(() => props.shots[Math.min(index.value, props.shots.length - 1)] ?? null)
const style = computed(() => ({ '--shot-accent': props.accent && /^#[0-9a-f]{6}$/i.test(props.accent) ? props.accent : 'var(--color-redstone-500)' }))
let lastFocus: HTMLElement | null = null

watch(() => props.shots, () => {
  if (index.value >= props.shots.length) index.value = 0
})

function step(delta: number) {
  const n = props.shots.length
  if (n > 0) index.value = (((index.value + delta) % n) + n) % n
}

function keyTarget(key: string): number | null {
  const n = props.shots.length
  if (n <= 1) return null
  if (key === 'ArrowLeft') return (index.value - 1 + n) % n
  if (key === 'ArrowRight') return (index.value + 1) % n
  if (key === 'Home') return 0
  if (key === 'End') return n - 1
  return null
}

function onKey(e: KeyboardEvent) {
  if (e.altKey || e.ctrlKey || e.metaKey) return
  if (zoomed.value && e.key === 'Escape') {
    e.preventDefault()
    close()
    return
  }
  const next = keyTarget(e.key)
  if (next === null) return
  e.preventDefault()
  index.value = next
}

async function open() {
  lastFocus = document.activeElement as HTMLElement | null
  zoomed.value = true
  await nextTick()
  closeButton.value?.focus()
}

function close() {
  zoomed.value = false
  lastFocus?.focus?.()
}

// Im Vollbild gelten die Tasten überall, sonst nur innerhalb der Galerie (@keydown unten).
function onWindowKey(e: KeyboardEvent) {
  if (zoomed.value) onKey(e)
}
onMounted(() => window.addEventListener('keydown', onWindowKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onWindowKey))

const label = (i: number) => fill(m.value.blog.imageOf, { n: i + 1, total: props.shots.length })
</script>

<template>
  <section v-if="current" class="shots" :style="style" :aria-label="m.blog.screenshots" aria-roledescription="carousel" @keydown="!zoomed && onKey($event)">
    <figure>
      <div class="stage group">
        <button type="button" class="stage-btn" :title="m.blog.enlarge" :aria-label="m.blog.enlarge" @click="open">
          <img :key="current.src" :src="current.src" :alt="current.caption" class="stage-img" decoding="async" />
        </button>
        <template v-if="shots.length > 1">
          <button type="button" class="nav-btn left-3" :title="m.blog.previous" :aria-label="m.blog.previous" @click="step(-1)">
            <SiteIcon name="back" class="size-5" />
          </button>
          <button type="button" class="nav-btn right-3" :title="m.blog.next" :aria-label="m.blog.next" @click="step(1)">
            <SiteIcon name="arrow" class="size-5" />
          </button>
          <span class="counter" aria-live="polite">{{ label(index) }}</span>
        </template>
        <span class="zoom-hint" aria-hidden="true"><SiteIcon name="fit" class="size-4" /></span>
      </div>
      <figcaption v-if="current.caption" class="mt-2 text-center text-sm text-base-400">{{ current.caption }}</figcaption>
    </figure>

    <ol v-if="shots.length > 1" class="mt-3 flex justify-center gap-2 overflow-x-auto pb-1">
      <li v-for="(shot, i) in shots" :key="shot.src" class="shrink-0">
        <button
          type="button"
          class="thumb"
          :class="{ 'thumb-on': i === index }"
          :aria-current="i === index ? 'true' : undefined"
          :aria-label="shot.caption ? `${label(i)}: ${shot.caption}` : label(i)"
          :title="shot.caption || undefined"
          @click="index = i"
        >
          <img :src="shot.src" alt="" loading="lazy" decoding="async" class="size-full object-cover" />
        </button>
      </li>
    </ol>

    <Teleport to="body">
      <Transition name="shot-fade">
        <div v-if="zoomed" class="lightbox" role="dialog" aria-modal="true" :aria-label="current.caption || m.blog.screenshots" @mousedown.self="close">
          <img :key="current.src" :src="current.src" :alt="current.caption" class="lightbox-img" />
          <div class="lightbox-text">
            <p v-if="current.caption" class="font-medium">{{ current.caption }}</p>
            <p v-if="shots.length > 1" class="mt-1 text-xs opacity-60">{{ index + 1 }} / {{ shots.length }}</p>
          </div>
          <button ref="closeButton" type="button" class="lb-btn top-4 right-4" :title="m.blog.closeImage" :aria-label="m.blog.closeImage" @click="close">
            <SiteIcon name="close" class="size-5" />
          </button>
          <template v-if="shots.length > 1">
            <button type="button" class="lb-btn top-1/2 left-4 -translate-y-1/2" :title="m.blog.previous" :aria-label="m.blog.previous" @click="step(-1)">
              <SiteIcon name="back" class="size-5" />
            </button>
            <button type="button" class="lb-btn top-1/2 right-4 -translate-y-1/2" :title="m.blog.next" :aria-label="m.blog.next" @click="step(1)">
              <SiteIcon name="arrow" class="size-5" />
            </button>
          </template>
        </div>
      </Transition>
    </Teleport>
  </section>
</template>

<style scoped>
.stage {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  width: 100%;
  border-radius: 0.75rem;
  border: 1px solid var(--color-base-800);
  background: #0c0b0e;
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--shot-accent) 18%, transparent), 0 18px 40px -24px var(--shot-accent);
}
.stage-btn {
  display: block;
  width: 100%;
  height: 100%;
  cursor: zoom-in;
}
.stage-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  animation: shot-in 0.18s ease-out;
}
@keyframes shot-in {
  from {
    opacity: 0.35;
  }
}
.nav-btn {
  position: absolute;
  top: 50%;
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  translate: 0 -50%;
  border-radius: 9999px;
  background: rgb(12 11 14 / 0.7);
  color: #f4f4f8;
  backdrop-filter: blur(6px);
  opacity: 0.85;
  transition: opacity 0.15s ease, background-color 0.15s ease;
}
.nav-btn:hover,
.nav-btn:focus-visible {
  opacity: 1;
  background: color-mix(in srgb, var(--shot-accent) 55%, rgb(12 11 14 / 0.8));
}
.counter {
  position: absolute;
  right: 0.75rem;
  bottom: 0.75rem;
  border-radius: 9999px;
  padding: 0.125rem 0.625rem;
  background: rgb(0 0 0 / 0.65);
  color: #fff;
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
}
.zoom-hint {
  position: absolute;
  top: 0.75rem;
  right: 0.75rem;
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  border-radius: 0.5rem;
  background: rgb(12 11 14 / 0.65);
  color: #f4f4f8;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}
.group:hover .zoom-hint {
  opacity: 1;
}
.thumb {
  display: block;
  width: 7rem;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: 0.5rem;
  border: 2px solid var(--color-base-700);
  background: #0c0b0e;
  opacity: 0.65;
  transition: opacity 0.15s ease, border-color 0.15s ease;
}
.thumb:hover,
.thumb:focus-visible {
  opacity: 1;
}
.thumb-on {
  opacity: 1;
  border-color: var(--shot-accent);
}
.lightbox {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  padding: 2.5rem 4rem;
  background: rgb(0 0 0 / 0.92);
  color: #f4f4f8;
}
.lightbox-img {
  max-width: 100%;
  max-height: 82vh;
  border-radius: 0.5rem;
  object-fit: contain;
  box-shadow: 0 25px 50px -12px rgb(0 0 0 / 0.6);
}
.lightbox-text {
  max-width: 48rem;
  text-align: center;
}
.lb-btn {
  position: absolute;
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  border-radius: 9999px;
  background: rgb(255 255 255 / 0.08);
  color: #f4f4f8;
  transition: background-color 0.15s ease;
}
.lb-btn:hover,
.lb-btn:focus-visible {
  background: rgb(255 255 255 / 0.18);
}
@media (max-width: 640px) {
  .lightbox {
    padding: 3.5rem 0.75rem 1.5rem;
  }
  .nav-btn {
    width: 2.25rem;
    height: 2.25rem;
  }
}
.shot-fade-enter-active,
.shot-fade-leave-active {
  transition: opacity 0.15s ease;
}
.shot-fade-enter-from,
.shot-fade-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .stage-img {
    animation: none;
  }
}
</style>
