<script setup lang="ts">
import type { Driver } from '~/utils/cosmetic-v2/format'
// Ein Abschnitt der Kosmetik-Seite für Teile im Format v2 (Hüte, Begleiter am Kopf, Flügel, Begleiter am Boden):
// Raster mit Studio-Karten + große 3D-Vorschau (HatViewer). Klick wählt aus, Überfahren (nur mit Maus) zeigt kurz in
// der Vorschau. Tag/Nacht und Animation teilen sich alle Abschnitte (v-model). Abschaltbare Teile (Optionen, z. B. Bart)
// lassen sich in der Vorschau umschalten – nur Anzeige, gespeichert wird im Launcher.
const props = defineProps<{
  id: string
  items: SiteHat[]
  error?: unknown
  title: string
  lead: string
  empty: string
  howTitle: string
  howText: string
  last?: boolean
}>()
const night = defineModel<boolean>('night', { default: false })
const animate = defineModel<boolean>('animate', { default: true })
const { lang, m, fill } = useLang()

const unlockLabel = (c: SiteHat) =>
  c.unlock === 'reward'
    ? m.value.cosmetics.halloweenReward
    : c.unlock === 'event'
      ? m.value.cosmetics.halloween
      : c.achievement
        ? fill(m.value.capes.achievement, { name: c.achievement[lang.value] ?? c.achievement.en })
        : c.unlock === 'free' ? m.value.capes.free : c.unlock === 'code' ? m.value.capes.code : m.value.capes.admin
const unlockClass = (c: SiteHat) =>
  c.unlock === 'event' || c.unlock === 'reward'
    ? 'badge-hw'
    : c.achievement
      ? 'bg-[#2a1f4a] text-[#c4a5ff]'
      : c.unlock === 'free' ? 'bg-ok/15 text-ok' : c.unlock === 'code' ? 'bg-lamp-900 text-lamp-300' : 'bg-redstone-900 text-redstone-300'

const pickedId = ref<string | null>(null)
const hoverId = ref<string | null>(null)
const selected = computed(() => props.items.find((h) => h.id === pickedId.value) ?? props.items[0] ?? null)
const shown = computed(() => props.items.find((h) => h.id === hoverId.value) ?? selected.value)
const loading = ref(false)

/** Wahl je Teil und Option (nur Vorschau). */
const choices = reactive<Record<string, Record<string, boolean>>>({})
const optionsOf = (h: SiteHat | null) => h?.options ?? []
const optionOn = (h: SiteHat, id: string) => choices[h.id]?.[id] ?? optionsOf(h).find((o) => o.id === id)?.default ?? true
function toggleOption(h: SiteHat, id: string) {
  choices[h.id] = { ...choices[h.id], [id]: !optionOn(h, id) }
}
const shownChoices = computed(() => (shown.value ? (choices[shown.value.id] ?? null) : null))
const optionName = (o: { id: string, name: Record<string, string> | null }) => o.name?.[lang.value] ?? o.name?.en ?? o.id

// Begleiter am Boden: Zustände, für die das Modell eine Animation hat (nur Vorschau; im Spiel wählt der Client selbst).
const STATE_NAMES: Record<string, Record<string, string>> = {
  en: { idle: 'Idle', walk: 'Walk', slide: 'Slide', swim: 'Swim', air: 'Jump', sneak: 'Sneak', grab: 'Grab', flee: 'Flee', handsup: 'Hands up' },
  de: { idle: 'Stehen', walk: 'Laufen', slide: 'Rutschen', swim: 'Schwimmen', air: 'Springen', sneak: 'Schleichen', grab: 'Schnappen', flee: 'Flüchten', handsup: 'Hände hoch' },
  es: { idle: 'Quieto', walk: 'Andar', slide: 'Deslizar', swim: 'Nadar', air: 'Saltar', sneak: 'Sigilo', grab: 'Agarrar', flee: 'Huir', handsup: 'Manos arriba' },
}
const STATE_GROUP: Record<string, string> = { en: 'Animation state', de: 'Zustand', es: 'Estado' }
const stateName = (s: string) => STATE_NAMES[lang.value]?.[s] ?? STATE_NAMES.en![s] ?? s
const petState = ref<string>('idle')
const shownStates = computed(() => shown.value?.states ?? [])
watch(() => shown.value?.id, () => (petState.value = 'idle'))
const viewerState = computed(() => (shownStates.value.includes(petState.value) && petState.value !== 'idle' ? petState.value as Driver : null))

let canHover = false
let hoverTimer: ReturnType<typeof setTimeout> | null = null
onMounted(() => {
  canHover = window.matchMedia('(hover: hover) and (pointer: fine)').matches
})
onBeforeUnmount(() => {
  if (hoverTimer) clearTimeout(hoverTimer)
})
function hover(id: string | null) {
  if (!canHover) return
  if (hoverTimer) clearTimeout(hoverTimer)
  // Kurz warten: beim schnellen Überstreichen nicht jedes Modell anzeigen.
  hoverTimer = setTimeout(() => (hoverId.value = id), id ? 140 : 60)
}
function pick(h: SiteHat) {
  pickedId.value = h.id
  hoverId.value = null
}
</script>

<template>
  <section :id="id" class="scroll-mt-24" :class="{ 'pb-16': last }" :aria-labelledby="`${id}-title`">
    <h2 :id="`${id}-title`" class="display text-3xl text-base-50">{{ title }}</h2>
    <p class="mt-2 max-w-2xl text-base-400">{{ lead }}</p>

    <p v-if="error" class="mt-8 text-lamp-300">{{ m.common.error }}</p>
    <p v-else-if="!items.length" class="mt-8 text-base-400">{{ empty }}</p>
    <div v-else class="mt-8 grid gap-6 lg:grid-cols-[1fr_22rem]">
      <ul class="grid grid-cols-2 content-start gap-3 sm:grid-cols-3" @pointerleave="hover(null)">
        <li v-for="h in items" :key="h.id">
          <button
            type="button"
            class="cos-card card hat-card"
            :class="{ 'cos-card-on': selected?.id === h.id }"
            :aria-pressed="selected?.id === h.id"
            :aria-label="fill(m.cosmetics.choose, { name: h.name })"
            @click="pick(h)"
            @pointerenter="hover(h.id)"
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
            <ClientOnly>
              <HatViewer :hat="shown" :night="night" :animate="animate" :options="shownChoices" :state="viewerState" :height="400" @loading="loading = $event" />
            </ClientOnly>
            <p class="absolute top-3 left-4 text-xs tracking-[0.18em] text-base-400 uppercase">{{ m.capes.preview }}</p>
            <p class="absolute right-4 bottom-3 text-xs text-base-600">{{ m.capes.dragHint }}</p>
            <span v-if="loading" class="spinner absolute top-3 right-4" aria-hidden="true" />
          </div>
          <div class="flex flex-wrap items-center gap-2 border-t border-base-800 px-5 py-3">
            <div class="seg" role="group" :aria-label="`${m.cosmetics.day} / ${m.cosmetics.night}`">
              <button type="button" :aria-pressed="!night" @click="night = false"><SiteIcon name="sun" class="size-3.5" />{{ m.cosmetics.day }}</button>
              <button type="button" :aria-pressed="night" @click="night = true"><SiteIcon name="moon" class="size-3.5" />{{ m.cosmetics.night }}</button>
            </div>
            <button type="button" class="tog" :aria-pressed="animate" @click="animate = !animate">
              <SiteIcon :name="animate ? 'pause' : 'play'" class="size-3.5" />{{ m.cosmetics.animation }}
            </button>
            <div v-if="shown && optionsOf(shown).length" class="flex flex-wrap gap-2" role="group" :aria-label="m.cosmetics.options">
              <button
                v-for="o in optionsOf(shown)"
                :key="o.id"
                type="button"
                class="tog"
                :aria-pressed="optionOn(shown, o.id)"
                @click="toggleOption(shown, o.id)"
              >
                <span class="opt-dot" aria-hidden="true" />{{ optionName(o) }}
              </button>
            </div>
          </div>
          <div v-if="shownStates.length > 1" class="flex flex-wrap gap-2 border-t border-base-800 px-5 py-3" role="group" :aria-label="STATE_GROUP[lang] ?? STATE_GROUP.en">
            <button v-for="st in shownStates" :key="st" type="button" class="tog" :aria-pressed="petState === st" @click="petState = st">{{ stateName(st) }}</button>
          </div>
          <div v-if="shown" class="border-t border-base-800 p-5">
            <p class="display text-2xl text-base-50">{{ shown.name }}</p>
            <span class="badge mt-2" :class="unlockClass(shown)">{{ unlockLabel(shown) }}</span>
          </div>
        </div>
        <div class="mt-5 p-1">
          <h3 class="font-semibold text-base-50">{{ howTitle }}</h3>
          <p class="mt-1.5 text-sm text-base-400">{{ howText }}</p>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.badge-hw {
  background: #2a1840;
  color: #ffb06a;
}
.cos-card {
  display: block;
  width: 100%;
  padding: 0.5rem 0.5rem 1rem;
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
.opt-dot {
  width: 0.55rem;
  height: 0.55rem;
  border-radius: 2px;
  border: 1px solid currentColor;
}
.tog[aria-pressed='true'] .opt-dot {
  background: currentColor;
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
