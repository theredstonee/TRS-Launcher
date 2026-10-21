<script setup lang="ts">
// Runder Creator-Kopf (Minecraft-Gesicht aus dem Mojang-Skin) mit Ring in der Farbe der Stufe.
withDefaults(defineProps<{ uuid: string, name: string, skin: string | null, tier: CreatorTier, size?: number }>(), { size: 72 })
</script>

<template>
  <span class="creator-avatar" :data-tier="tier" :style="{ width: `${size + 8}px`, height: `${size + 8}px` }">
    <span class="face" :style="{ width: `${size}px`, height: `${size}px` }">
      <PlayerHead :uuid="uuid" :name="name" :skin="skin" :fetch="false" :size="size" />
    </span>
  </span>
</template>

<style scoped>
.creator-avatar {
  --ring: var(--color-base-700);
  position: relative;
  display: inline-grid;
  flex-shrink: 0;
  place-items: center;
  border-radius: 999px;
  background: conic-gradient(from 210deg, var(--ring), color-mix(in srgb, var(--ring) 35%, transparent), var(--ring));
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.creator-avatar[data-tier='top'] {
  --ring: var(--color-lamp-400);
}
.creator-avatar[data-tier='partner'] {
  --ring: var(--color-redstone-500);
}
.face {
  display: block;
  overflow: hidden;
  border-radius: 999px;
  background: var(--color-base-950);
  box-shadow: inset 0 0 0 2px var(--color-base-950);
}
.face :deep(.head) {
  border-radius: 0;
}
</style>
