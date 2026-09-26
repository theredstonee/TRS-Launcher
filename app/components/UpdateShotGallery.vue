<script setup lang="ts">
// Screenshot-Galerie eines Update-Beitrags: großes Bild mit Bildunterschrift, Pfeile, Vorschaubilder darunter.
// Klick aufs Bild → Vollbild. ←/→ (Pos1/Ende) blättern im Dialog wie im Vollbild; Esc schließt im Vollbild nur
// das Bild, nicht den Dialog. Bilder kommen ausschließlich aus dem mitgelieferten Ordner /news/.
const props = withDefaults(defineProps<{ shots: PostShot[]; accent?: string | null }>(), { accent: null })
const index = defineModel<number>({ default: 0 })

const zoomed = ref(false)
const closeButton = ref<HTMLButtonElement | null>(null)
const current = computed(() => props.shots[Math.min(index.value, props.shots.length - 1)] ?? null)
const style = computed(() => ({ '--shot-accent': props.accent && /^#[0-9a-f]{6}$/i.test(props.accent) ? props.accent : 'var(--color-redstone-500)' }))
let lastFocus: HTMLElement | null = null

watch(
  () => props.shots,
  () => {
    if (index.value >= props.shots.length) index.value = 0
  },
)

function step(delta: number) {
  index.value = stepShot(index.value, delta, props.shots.length)
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

function onKey(e: KeyboardEvent) {
  if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return
  const target = e.target as HTMLElement | null
  if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
  if (zoomed.value && e.key === 'Escape') {
    // Nur das Vollbild schließen – der Dialog darunter hört auch auf Esc.
    e.stopImmediatePropagation()
    e.preventDefault()
    close()
    return
  }
  const next = shotKey(e.key, index.value, props.shots.length)
  if (next === null) return
  e.preventDefault()
  if (zoomed.value) e.stopImmediatePropagation()
  index.value = next
}
// Capture-Phase, damit Esc im Vollbild vor dem Esc-Handler des Dialogs ankommt.
onMounted(() => window.addEventListener('keydown', onKey, true))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey, true))
</script>

<template>
  <section v-if="current" class="shots" :style="style" :aria-label="t('updateNews.screenshots')" aria-roledescription="carousel">
    <figure class="space-y-2">
      <div class="stage group relative overflow-hidden rounded-xl border border-base-700 bg-base-950">
        <button type="button" class="block size-full cursor-zoom-in" :title="t('updateNews.enlarge')" :aria-label="t('updateNews.enlarge')" @click="open">
          <img :key="current.src" :src="current.src" :alt="current.caption" class="shot-img size-full object-contain" decoding="async" />
        </button>
        <template v-if="shots.length > 1">
          <button type="button" class="nav-btn left-2" :title="t('updateNews.previous')" :aria-label="t('updateNews.previous')" @click="step(-1)">
            <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M15 5l-7 7 7 7" /></svg>
          </button>
          <button type="button" class="nav-btn right-2" :title="t('updateNews.next')" :aria-label="t('updateNews.next')" @click="step(1)">
            <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="m9 5 7 7-7 7" /></svg>
          </button>
          <span class="badge absolute right-3 bottom-3 bg-black/65 text-[11px] text-white tabular-nums backdrop-blur" aria-live="polite">
            {{ t('updateNews.imageOf', { n: index + 1, total: shots.length }) }}
          </span>
        </template>
        <span class="zoom-hint" aria-hidden="true">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" /></svg>
        </span>
      </div>
      <figcaption v-if="current.caption" class="text-sm text-base-300">{{ current.caption }}</figcaption>
    </figure>

    <ol v-if="shots.length > 1" class="mt-3 flex gap-2 overflow-x-auto pb-1">
      <li v-for="(shot, i) in shots" :key="shot.src" class="shrink-0">
        <button
          type="button"
          class="thumb"
          :class="{ 'thumb-on': i === index }"
          :aria-current="i === index ? 'true' : undefined"
          :aria-label="shot.caption ? `${t('updateNews.imageOf', { n: i + 1, total: shots.length })}: ${shot.caption}` : t('updateNews.imageOf', { n: i + 1, total: shots.length })"
          :title="shot.caption || undefined"
          @click="index = i"
        >
          <img :src="shot.src" alt="" loading="lazy" decoding="async" class="size-full object-cover" />
        </button>
      </li>
    </ol>

    <Teleport to="body">
      <Transition name="shot-fade">
        <div
          v-if="zoomed"
          class="fixed inset-0 z-[60] flex flex-col items-center justify-center gap-3 bg-black/90 px-16 py-10"
          role="dialog"
          aria-modal="true"
          :aria-label="current.caption || t('updateNews.screenshots')"
          @mousedown.self="close"
        >
          <img :key="current.src" :src="current.src" :alt="current.caption" class="max-h-[82vh] max-w-full rounded-lg object-contain shadow-2xl" />
          <div class="max-w-3xl text-center">
            <p v-if="current.caption" class="font-medium text-base-50">{{ current.caption }}</p>
            <p v-if="shots.length > 1" class="mt-1 text-xs text-base-400 tabular-nums">{{ index + 1 }} / {{ shots.length }}</p>
          </div>
          <button ref="closeButton" type="button" class="btn-icon absolute top-4 right-4" :title="t('updateNews.closeImage')" :aria-label="t('updateNews.closeImage')" @click="close">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
          <template v-if="shots.length > 1">
            <button type="button" class="btn-icon absolute top-1/2 left-4 size-11 -translate-y-1/2" :title="t('updateNews.previous')" :aria-label="t('updateNews.previous')" @click="step(-1)">
              <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M15 5l-7 7 7 7" /></svg>
            </button>
            <button type="button" class="btn-icon absolute top-1/2 right-4 size-11 -translate-y-1/2" :title="t('updateNews.next')" :aria-label="t('updateNews.next')" @click="step(1)">
              <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m9 5 7 7-7 7" /></svg>
            </button>
          </template>
        </div>
      </Transition>
    </Teleport>
  </section>
</template>

<style scoped>
.stage {
  aspect-ratio: 16 / 9;
  max-height: min(44vh, 30rem);
  width: 100%;
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--shot-accent) 18%, transparent), 0 12px 32px -18px var(--shot-accent);
}
.shot-img {
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
  width: 2.5rem;
  height: 2.5rem;
  translate: 0 -50%;
  border-radius: 9999px;
  background: rgb(12 11 14 / 0.7);
  color: var(--color-base-50);
  backdrop-filter: blur(6px);
  opacity: 0.8;
  transition: opacity 0.15s ease, background-color 0.15s ease;
}
.nav-btn:hover,
.nav-btn:focus-visible {
  opacity: 1;
  background: color-mix(in srgb, var(--shot-accent) 55%, rgb(12 11 14 / 0.8));
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
  color: var(--color-base-100);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}
.group:hover .zoom-hint {
  opacity: 1;
}
.thumb {
  display: block;
  width: 6.5rem;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: 0.5rem;
  border: 2px solid var(--color-base-700);
  background: var(--color-base-950);
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
.shot-fade-enter-active,
.shot-fade-leave-active {
  transition: opacity 0.15s ease;
}
.shot-fade-enter-from,
.shot-fade-leave-to {
  opacity: 0;
}
</style>
