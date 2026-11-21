<script setup lang="ts">
// Rundes Partner-Logo (128×128 vom Server) oder Kürzel, solange es keins gibt.
const props = withDefaults(defineProps<{ logo: string | null, name: string, size?: number }>(), { size: 72 })
const initials = computed(() => partnerInitials(props.name))
</script>

<template>
  <span class="partner-logo" :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size / 2.8)}px` }">
    <img v-if="logo" :src="logo" :alt="name" :width="size" :height="size" loading="lazy" decoding="async" />
    <span v-else aria-hidden="true">{{ initials }}</span>
  </span>
</template>

<style scoped>
.partner-logo {
  display: inline-grid;
  place-items: center;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 999px;
  border: 2px solid var(--color-base-700);
  background: var(--color-base-850);
  color: var(--color-base-200);
  font-weight: 800;
  letter-spacing: 0.02em;
  transition: transform 0.15s, border-color 0.15s, box-shadow 0.15s;
}
.partner-logo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
