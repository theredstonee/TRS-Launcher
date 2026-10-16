<script setup lang="ts">
import { creatorTiers, tierPerks, type CreatorTier } from '~/utils/creator'

// Die drei Stufen nebeneinander (Voraussetzung + Vorteile); `current` hebt die eigene hervor.
defineProps<{ current?: CreatorTier | null }>()
</script>

<template>
  <div class="grid gap-3 md:grid-cols-3" data-testid="creator-tiers">
    <div
      v-for="tier in creatorTiers"
      :key="tier"
      class="rounded-xl border bg-base-900 p-4"
      :class="current === tier ? 'border-redstone-500 shadow-[0_0_0_3px_color-mix(in_srgb,var(--color-redstone-500)_18%,transparent)]' : 'border-base-800'"
    >
      <div class="flex flex-wrap items-center gap-2">
        <CreatorTierBadge :tier="tier" />
        <span v-if="current === tier" class="text-xs text-base-400">{{ t('creator.tiers.yours') }}</span>
      </div>
      <p class="mt-2 text-xs text-base-400">{{ t(`creator.tiers.${tier}.need`) }}</p>
      <ul class="mt-2 list-disc space-y-1 pl-4 text-[13px] text-base-200">
        <li v-for="perk in tierPerks[tier]" :key="perk">{{ t(perk) }}</li>
      </ul>
    </div>
  </div>
</template>
