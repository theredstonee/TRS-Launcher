<script setup lang="ts">
// Status-Abzeichen eines Issues (§28): Farbe je Status, geschlossene gedämpft.
import type { IssueStatus } from '#shared/issues'

defineProps<{ status: IssueStatus }>()
const { it } = useIssueText()
</script>

<template>
  <span class="badge issue-status" :data-s="status">
    <span class="dot" aria-hidden="true" />{{ it.statuses[status] }}
  </span>
</template>

<style scoped>
.issue-status {
  --c: var(--color-base-400);
  color: var(--c);
  background: color-mix(in srgb, var(--c) 14%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c) 30%, transparent);
}
.dot {
  width: 0.4rem;
  height: 0.4rem;
  border-radius: 1px;
  background: var(--c);
}
.issue-status[data-s='open'] { --c: var(--color-base-200); }
.issue-status[data-s='planned'] { --c: #7cc4ff; }
.issue-status[data-s='in_progress'] { --c: var(--color-lamp-400); }
.issue-status[data-s='in_progress'] .dot { box-shadow: 0 0 6px var(--color-lamp-400); }
.issue-status[data-s='in_review'] { --c: #c4a5ff; }
.issue-status[data-s='done'] { --c: var(--color-ok); }
.issue-status[data-s='rejected'] { --c: var(--color-redstone-300); }
.issue-status[data-s='duplicate'] { --c: var(--color-base-400); }
</style>
