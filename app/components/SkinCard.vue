<script setup lang="ts">
import type { ThumbRequest } from '~/utils/skinThumbs'

// Eine Karte der Skins-Seite: 3D-Figur als fertiges Bild (einmal gerendert,
// siehe utils/skinThumbs), Name nur als Tooltip, Haken bei der Auswahl und
// Aktionen beim Drüberfahren. Statt der Figur kann auch ein eigenes Bild
// kommen (`image`, z. B. Kopf-Kosmetik-Karten vom TRS-Server).
const props = withDefaults(
  defineProps<{
    /** Was gerendert werden soll; `null` = Platzhalter bzw. `image`. */
    thumb?: ThumbRequest | null
    /** Fertiges Bild statt der gerenderten Figur. */
    image?: string | null
    title: string
    selected?: boolean
    /** Gesperrt (noch nicht freigeschaltet): Bild gedämpft, Schloss unten rechts. */
    locked?: boolean
    /** Hintergrund nachts (Kosmetik-Karten zeigen dann das Nacht-Bild). */
    night?: boolean
    /** Seitenverhältnis des Bildbereichs. */
    aspect?: 'portrait' | 'square'
  }>(),
  { thumb: null, image: null, selected: false, locked: false, night: false, aspect: 'portrait' },
)
const emit = defineEmits<{ select: [] }>()

const rendered = useSkinThumb(() => (props.image ? null : props.thumb))
const src = computed(() => props.image ?? rendered.value)
</script>

<template>
  <div class="skin-card group relative" :class="{ 'skin-card-on': selected }" :data-selected="selected || undefined">
    <button
      class="block w-full rounded-xl text-left outline-none focus-visible:ring-2 focus-visible:ring-redstone-400"
      :title="title"
      :aria-label="title"
      :aria-pressed="selected"
      @click="emit('select')"
    >
      <span
        class="skin-card-stage relative block overflow-hidden rounded-xl bg-gradient-to-b"
        :class="[
          aspect === 'square' ? 'aspect-square' : 'aspect-[3/4]',
          night ? 'from-[#0f1222] to-[#05060b]' : 'from-base-800 to-base-950',
        ]"
      >
        <img
          v-if="src"
          :src="src"
          alt=""
          draggable="false"
          class="size-full object-contain transition-transform duration-300 group-hover:scale-[1.05]"
          :class="{ 'opacity-50 grayscale-[0.7]': locked, 'object-cover': !!image }"
        />
        <span v-else-if="thumb || image" class="skeleton absolute inset-2 rounded-lg" />
        <slot v-else name="placeholder" />
        <!-- Boden-Schatten unter der Figur -->
        <span v-if="!image" class="pointer-events-none absolute inset-x-6 bottom-2.5 h-2 rounded-[50%] bg-black/35 blur-[3px]" aria-hidden="true" />
        <span
          v-if="selected"
          class="absolute top-1.5 right-1.5 grid size-5 place-items-center rounded-full bg-redstone-500 text-white shadow-[0_0_8px_var(--color-redstone-500)]"
          aria-hidden="true"
        >
          <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"><path d="m5 12.5 4.5 4.5L19 7.5" /></svg>
        </span>
        <svg
          v-if="locked"
          viewBox="0 0 24 24"
          class="absolute right-1.5 bottom-1.5 size-5 rounded bg-base-900/90 p-0.5 text-base-200"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <path d="M7 11V8a5 5 0 0 1 10 0v3M5 11h14v10H5z" />
        </svg>
        <span class="pointer-events-none absolute top-1.5 left-1.5 flex max-w-[calc(100%-2.25rem)] flex-col items-start gap-1">
          <slot name="badges" />
        </span>
      </span>
    </button>
    <div
      v-if="$slots.actions"
      class="pointer-events-none absolute inset-x-1.5 bottom-1.5 flex justify-center gap-1 opacity-0 transition-opacity duration-150 group-hover:pointer-events-auto group-hover:opacity-100 group-focus-within:pointer-events-auto group-focus-within:opacity-100"
    >
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.skin-card-stage {
  box-shadow:
    inset 0 0 0 1px var(--color-base-800),
    inset 0 1px 0 rgb(255 255 255 / 0.03);
  transition: box-shadow 0.15s;
}
.group:hover .skin-card-stage {
  box-shadow:
    inset 0 0 0 1px var(--color-base-600),
    inset 0 1px 0 rgb(255 255 255 / 0.05);
}
/* Ausgewählt: Rahmen „unter Strom“. */
.skin-card-on .skin-card-stage {
  box-shadow:
    inset 0 0 0 2px var(--color-redstone-500),
    0 0 14px -4px color-mix(in srgb, var(--color-redstone-500) 80%, transparent);
}
</style>
