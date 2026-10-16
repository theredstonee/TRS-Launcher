<script setup lang="ts">
import { tierClass, type CreatorTier } from '~/utils/creator'

// Plakette der Creator-Stufe (Starter hellblau, Partner violett, Top gold).
defineProps<{ tier: CreatorTier; star?: boolean }>()
</script>

<template>
  <span class="badge uppercase tracking-wide ring-1" :class="tierClass[tier]">
    <SocialIcon v-if="star" name="star" class="size-3" />{{ t(`creator.tiers.${tier}.name`) }}
  </span>
</template>
