<script setup lang="ts">
import { ACCESS_MODES, usagePercent } from '~/utils/craftinghosting'

// Übersicht: Inhalte übernehmen, Hinweise, Live-Kacheln und Zugang.
const store = useCraftingHostingStore()
const server = computed(() => store.server!)
const stats = computed(() => store.stats)
const MB = 1024 * 1024

const showTransfer = computed(
  () => !!store.origin && (!!store.transfer || (!store.origin.transferred && server.value.setup === 'done' && !!server.value.lastStartAt)),
)

const tiles = computed(() => {
  const s = stats.value
  const r = server.value.resources
  const memLimit = s?.memory.limitBytes ?? r.memoryMb * MB
  const diskLimit = s?.disk.limitBytes ?? r.diskMb * MB
  const of = (limit: number) => t('craftinghosting.tiles.of', { limit: formatBytes(limit) })
  return [
    {
      id: 'cpu',
      label: t('craftinghosting.stats.cpu'),
      value: s?.cpuPercent != null ? `${s.cpuPercent} %` : '–',
      detail: t('craftinghosting.tiles.of', { limit: `${r.cpuPercent} %` }),
      percent: usagePercent(s?.cpuPercent, r.cpuPercent),
    },
    {
      id: 'memory',
      label: t('craftinghosting.stats.memory'),
      value: s?.memory.usedBytes != null ? formatBytes(s.memory.usedBytes) : '–',
      detail: of(memLimit),
      percent: usagePercent(s?.memory.usedBytes, memLimit),
    },
    {
      id: 'disk',
      label: t('craftinghosting.stats.disk'),
      value: s?.disk.usedBytes != null ? formatBytes(s.disk.usedBytes) : '–',
      detail: of(diskLimit),
      percent: usagePercent(s?.disk.usedBytes, diskLimit),
    },
    {
      id: 'players',
      label: t('craftinghosting.stats.players'),
      value: s?.players ? String(s.players.online) : '–',
      detail: s?.players ? t('craftinghosting.tiles.of', { limit: String(s.players.max) }) : undefined,
      percent: s?.players ? usagePercent(s.players.online, s.players.max) : null,
    },
  ]
})

type Hint = { key: string; text: string; tone: 'info' | 'warn' | 'bad'; testid?: string }
const hints = computed<Hint[]>(() => {
  const s = server.value
  const out: Hint[] = []
  if (s.state === 'installing') out.push({ key: 'installing', text: t('craftinghosting.notes.installing'), tone: 'info' })
  else if (s.state === 'failed') out.push({ key: 'failed', text: t('craftinghosting.notes.failed'), tone: 'bad' })
  else if (s.state === 'suspended') out.push({ key: 'suspended', text: t('craftinghosting.notes.suspended'), tone: 'bad' })
  if (s.setup === 'restarting') out.push({ key: 'setup', text: t('craftinghosting.setupRestarting'), tone: 'warn', testid: 'hosting-setup' })
  if (s.session.active && !s.session.hostPresent) out.push({ key: 'away', text: t('craftinghosting.hostAway'), tone: 'warn' })
  if (!s.session.active && s.session.lastStopReason) out.push({ key: 'reason', text: t(`craftinghosting.stopReason.${s.session.lastStopReason}`), tone: 'info' })
  if (s.deleteAt && !s.session.active) {
    out.push({ key: 'deleteAt', text: t('craftinghosting.deleteAt', { date: formatDate(s.deleteAt) }), tone: 'info', testid: 'hosting-delete-at' })
  }
  return out
})
const hintTone = { info: 'text-base-200', warn: 'text-lamp-300', bad: 'text-redstone-300' }
const dotTone = { info: 'bg-base-600', warn: 'bg-lamp-400', bad: 'bg-redstone-400' }
</script>

<template>
  <div class="space-y-4">
    <CraftinghostingTransferCard v-if="showTransfer" />

    <ul v-if="hints.length" class="card divide-y divide-base-800 px-4" data-testid="hosting-hints">
      <li v-for="h in hints" :key="h.key" class="flex items-start gap-2.5 py-2.5 text-sm" :class="hintTone[h.tone]" :data-testid="h.testid">
        <span class="mt-1.5 size-1.5 shrink-0 rounded-full" :class="dotTone[h.tone]" aria-hidden="true" />
        {{ h.text }}
      </li>
    </ul>

    <section aria-labelledby="ch-live-title">
      <div class="mb-2 flex items-baseline justify-between gap-2">
        <h3 id="ch-live-title" class="text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t('craftinghosting.stats.title') }}</h3>
        <p v-if="!stats" class="text-[11px] text-base-400">{{ t('craftinghosting.tiles.offHint') }}</p>
      </div>
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4" data-testid="hosting-stats">
        <CraftinghostingStatTile v-for="tile in tiles" :key="tile.id" :label="tile.label" :value="tile.value" :detail="tile.detail" :percent="tile.percent">
          <template #icon>
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <template v-if="tile.id === 'cpu'"><rect x="7" y="7" width="10" height="10" rx="1.5" /><path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4" /></template>
              <template v-else-if="tile.id === 'memory'"><rect x="3" y="7" width="18" height="10" rx="1.5" /><path d="M7 10.5v3M11 10.5v3M15 10.5v3" /></template>
              <template v-else-if="tile.id === 'disk'"><ellipse cx="12" cy="6.5" rx="7.5" ry="2.8" /><path d="M4.5 6.5v11c0 1.5 3.4 2.8 7.5 2.8s7.5-1.3 7.5-2.8v-11" /></template>
              <template v-else><circle cx="12" cy="8" r="3.5" /><path d="M5 20c.7-3.6 3.4-5.5 7-5.5s6.3 1.9 7 5.5" /></template>
            </svg>
          </template>
        </CraftinghostingStatTile>
      </div>
    </section>

    <section class="card p-4" aria-labelledby="ch-access-title">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="min-w-0">
          <h3 id="ch-access-title" class="text-sm font-semibold text-base-50">{{ t('craftinghosting.access.label') }}</h3>
          <p class="mt-0.5 text-xs text-base-400">{{ t(`craftinghosting.access.${server.access}Hint`) }}</p>
        </div>
        <div class="flex rounded-full bg-base-950 p-1 ring-1 ring-base-800" role="radiogroup" :aria-label="t('craftinghosting.access.label')">
          <button
            v-for="mode in ACCESS_MODES"
            :key="mode"
            class="seg rounded-full text-xs"
            :class="{ 'seg-on': server.access === mode }"
            role="radio"
            :aria-checked="server.access === mode"
            :disabled="!!store.busy"
            @click="store.setAccess(mode)"
          >
            {{ t(`craftinghosting.access.${mode}`) }}
          </button>
        </div>
      </div>
    </section>
  </div>
</template>
