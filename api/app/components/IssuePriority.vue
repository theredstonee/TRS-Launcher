<script setup lang="ts">
// Priorität als Punkt (+ optional Text): kritisch glüht wie Redstone, hoch Lampe, mittel Blau, niedrig grau.
import type { IssuePriority } from '#shared/issues'

withDefaults(defineProps<{ priority: IssuePriority | null, label?: boolean }>(), { label: true })
const { it } = useIssueText()
</script>

<template>
  <span class="prio" :data-p="priority ?? 'none'" :title="it.priorities[priority ?? 'none']">
    <span class="dot" aria-hidden="true" /><span v-if="label">{{ it.priorities[priority ?? 'none'] }}</span><span v-else class="sr-only">{{ it.priorities[priority ?? 'none'] }}</span>
  </span>
</template>

<style scoped>
.prio {
  --c: var(--color-base-400);
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 11px;
  line-height: 1rem;
  font-weight: 600;
  color: var(--c);
  white-space: nowrap;
}
.dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 2px;
  background: var(--c);
}
.prio[data-p='critical'] {
  --c: var(--color-redstone-400);
}
.prio[data-p='critical'] .dot {
  box-shadow: 0 0 6px var(--color-redstone-500);
}
.prio[data-p='high'] {
  --c: var(--color-lamp-400);
}
.prio[data-p='medium'] {
  --c: #7cc4ff;
}
.prio[data-p='low'] {
  --c: var(--color-base-200);
}
.prio[data-p='none'] {
  --c: var(--color-base-600);
}
</style>
