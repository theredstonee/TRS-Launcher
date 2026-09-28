<script setup lang="ts">
// Bereich eines Issues als farbige Pille: Launcher = Redstone, TRS Client = Lampe, Website = Kupfer-Türkis.
import type { IssueArea } from '#shared/issues'

defineProps<{ area: IssueArea }>()
const { it } = useIssueText()
</script>

<template>
  <span class="area-pill" :data-a="area">{{ it.areas[area] }}</span>
</template>

<style scoped>
.area-pill {
  --c: var(--color-redstone-300);
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  padding: 0.05rem 0.5rem;
  border-radius: 999px;
  font-size: 11px;
  line-height: 1rem;
  font-weight: 600;
  white-space: nowrap;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 12%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c) 38%, transparent);
}
.area-pill[data-a='client'] {
  --c: var(--color-lamp-300);
}
.area-pill[data-a='website'] {
  --c: #5fd4c4;
}
</style>
