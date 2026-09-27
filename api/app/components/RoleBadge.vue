<script setup lang="ts">
// Rollen-Abzeichen mit Farbe; feste Rollen tragen den übersetzten Standardnamen, eigene ihren Namen.
const props = defineProps<{ role: { id: string, name: string | null, color: string }, small?: boolean }>()
const { t } = useTeamText()
const label = computed(() => props.role.name ?? t.value.adm.roleNames[props.role.id] ?? props.role.id)
</script>

<template>
  <span class="role-badge" :class="{ small }" :style="{ '--role': role.color }">
    <span class="dot" aria-hidden="true" />{{ label }}
  </span>
</template>

<style scoped>
.role-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  border: 1px solid color-mix(in srgb, var(--role) 55%, transparent);
  background: color-mix(in srgb, var(--role) 14%, transparent);
  color: color-mix(in srgb, var(--role) 55%, white);
  border-radius: 999px;
  padding: 0.15rem 0.6rem;
  font-size: 0.8rem;
  font-weight: 600;
  white-space: nowrap;
}
.role-badge.small {
  padding: 0.05rem 0.45rem;
  font-size: 0.7rem;
}
.dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 999px;
  background: var(--role);
  box-shadow: 0 0 8px var(--role);
}
</style>
