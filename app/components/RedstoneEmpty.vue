<script setup lang="ts">
// Leerer Zustand im Redstone-Stil: oben eine kleine, ruhige Schaltung,
// darunter Titel, Text und Aktionen (Slot). Für „Noch keine …“ auf allen Seiten.
withDefaults(
  defineProps<{
    title: string
    text?: string
    /** Andere Szene je Seite, damit nicht überall dieselbe Schaltung läuft. */
    seed?: number
    /** Kompakter (z. B. „Nichts gefunden“ in Listen). */
    compact?: boolean
  }>(),
  { text: '', seed: 0x51, compact: false },
)
</script>

<template>
  <div class="card relative flex flex-col items-center overflow-hidden px-6 text-center" :class="compact ? 'pb-8' : 'pb-12'">
    <div class="empty-scene relative w-full" :class="compact ? 'h-20' : 'h-32'">
      <RedstoneScene fill :seed="seed" />
    </div>
    <h2 class="heading relative mt-4 text-lg">{{ title }}</h2>
    <p v-if="text" class="relative mx-auto mt-1.5 max-w-md text-sm text-base-400">{{ text }}</p>
    <div v-if="$slots.default" class="relative mt-5 flex flex-wrap justify-center gap-2">
      <slot />
    </div>
  </div>
</template>

<style scoped>
/* Die Schaltung läuft oben aus – weich in die Karte übergehen. */
.empty-scene {
  margin-inline: -1.5rem;
  width: calc(100% + 3rem);
  opacity: 0.75;
  mask-image: linear-gradient(180deg, #000 30%, transparent 100%);
}
</style>
