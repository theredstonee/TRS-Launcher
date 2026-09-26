<script setup lang="ts">
import type { AuditEntry } from '~/utils/moderation'

// Ein Eintrag im Audit-Log: wann, wer, was, an wem (verlinkt zur Akte).
defineProps<{ entry: AuditEntry; compact?: boolean }>()

function actor(e: AuditEntry): string {
  if (e.actor === 'system') return t('team.common.system')
  if (e.actor === 'api-key') return t('team.common.apiKey')
  return e.actorName || e.actor
}
</script>

<template>
  <div class="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5 text-xs">
    <span class="shrink-0 text-base-400 tabular-nums" :title="formatDate(entry.at)">{{ compact ? formatRelative(entry.at, true) : formatDate(entry.at) }}</span>
    <span class="font-medium text-base-100">{{ actor(entry) }}</span>
    <span class="rounded bg-base-800 px-1.5 font-mono text-[11px] text-base-200">{{ entry.action }}</span>
    <NuxtLink v-if="entry.target && /^[0-9a-f]{32}$/.test(entry.target)" :to="`/admin/players/${entry.target}`" class="text-redstone-300 hover:underline">
      {{ entry.targetName || entry.target }}
    </NuxtLink>
    <span v-else-if="entry.target" class="text-base-200">{{ entry.targetName || entry.target }}</span>
    <span v-if="entry.detail" class="min-w-0 truncate text-base-400" :title="entry.detail">· {{ entry.detail }}</span>
  </div>
</template>
