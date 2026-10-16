<script setup lang="ts">
import { PUMPKIN_TOTAL } from '~/utils/halloween'

// Startseite, oben rechts (Entwurf `.evcard`): Halloween 2026 bis 2. November, Kürbis-Suche
// n/13 als Mini-Kürbisse und „Süßes oder Saures?“. Ohne Stand von der API (alt/offline)
// bleibt nur der Titel – der Look funktioniert trotzdem.
const halloween = useHalloweenStore()
const open = ref(false)
const slots = computed(() => Array.from({ length: halloween.state?.pumpkins.total ?? PUMPKIN_TOTAL }, (_, i) => i < halloween.found))
</script>

<template>
  <div class="card hw-evcard" data-testid="halloween-card">
    <div class="flex items-center gap-2">
      <HalloweenPixelSprite name="moonbat" class="h-5 w-[22px]" />
      <h3 class="display m-0 text-[19px] leading-tight text-base-50">{{ t('halloween.card.title') }}</h3>
    </div>
    <p class="hw-italic text-[17px] leading-snug text-redstone-300">{{ t('halloween.card.until') }}</p>
    <template v-if="halloween.state">
      <p class="mt-2 text-xs text-base-400">{{ t('halloween.card.hunt', { n: halloween.found, total: halloween.total }) }}</p>
      <div class="mt-2 mb-3 flex flex-wrap gap-0.5" :aria-label="t('halloween.card.hunt', { n: halloween.found, total: halloween.total })">
        <HalloweenPixelSprite v-for="(f, i) in slots" :key="i" name="pumpkin" class="size-4" :class="{ 'hw-unfound': !f }" />
      </div>
      <div class="flex flex-wrap items-center gap-2.5">
        <button type="button" class="btn btn-primary h-8 px-3 text-sm" data-testid="halloween-trick-open" @click="open = true">{{ t('halloween.trick.button') }}</button>
        <span class="text-xs text-base-400">{{ halloween.trickOpen ? t('halloween.trick.openToday') : t('halloween.trick.doneToday') }}</span>
      </div>
    </template>
    <p v-else class="mt-2 text-xs text-base-400">{{ t('halloween.card.noData') }}</p>
    <!-- Teleport: das backdrop-filter der Karte würde den festen Dialog sonst in der Karte einsperren. -->
    <Teleport to="body">
      <HalloweenTrickDialog v-if="open" @close="open = false" />
    </Teleport>
  </div>
</template>

<style scoped>
.hw-evcard {
  padding: 16px;
  background-color: rgb(20 16 33 / 0.8);
  border-color: #2a2440;
  backdrop-filter: blur(6px);
}
.hw-unfound {
  filter: brightness(0) invert(0.2);
}
:root[data-theme="light"] .hw-evcard {
  background-color: rgb(255 255 255 / 0.85);
  border-color: var(--color-base-700);
}
</style>
