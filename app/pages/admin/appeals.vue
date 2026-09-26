<script setup lang="ts">
import { reasonLabel } from '~/utils/sanctions'
import type { AdminAppeal } from '~/utils/team'

// Einsprüche: offene zuerst (älteste oben), dazu entschiedene. Tasten j/k,
// Enter (entscheiden), a (aufheben), r (bestätigen).
const toasts = useToasts()
const team = useTeam()
const trs = useTrsStore()
const status = ref<'open' | 'decided' | 'all'>('open')
const appeals = ref<AdminAppeal[] | null>(null)
const cursor = ref<string | null>(null)
const openCount = ref(0)
const loadingMore = ref(false)
const deciding = ref<{ appeal: AdminAppeal; decision: 'lift' | 'shorten' | 'uphold' } | null>(null)
const items = computed(() => appeals.value ?? [])

async function load(more = false) {
  if (more) loadingMore.value = true
  try {
    const page = await backend.team.appeals({ status: status.value, cursor: more ? (cursor.value ?? undefined) : undefined, limit: 30 })
    appeals.value = more ? [...(appeals.value ?? []), ...page.appeals] : page.appeals
    cursor.value = page.nextCursor
    openCount.value = page.open
  } catch (e) {
    if (!more) appeals.value = []
    toasts.error(e)
  } finally {
    loadingMore.value = false
  }
}
onMounted(() => void load())
watch(status, () => {
  appeals.value = null
  void load()
})

/** Moderatoren entscheiden nicht über Einsprüche gegen eigene Strafen. */
const own = (a: AdminAppeal) => !team.isAdmin.value && a.sanction.createdBy.uuid === trs.me?.uuid
function decide(a: AdminAppeal, decision: 'lift' | 'shorten' | 'uphold' = 'uphold') {
  if (a.status === 'open' && !own(a)) deciding.value = { appeal: a, decision }
}
async function decided() {
  deciding.value = null
  toasts.ok(t('team.appeals.decided'))
  await Promise.all([load(), team.refreshCounts()])
}

const { active } = useListKeys(items, {
  open: (a) => decide(a),
  onA: (a) => decide(a, 'lift'),
  onR: (a) => decide(a, 'uphold'),
})
</script>

<template>
  <section :aria-label="t('team.nav.appeals')" data-testid="admin-appeals">
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs">
        <button v-for="s in (['open', 'decided', 'all'] as const)" :key="s" class="seg rounded-md" :class="{ 'seg-on': status === s }" :aria-pressed="status === s" @click="status = s">
          {{ t(`team.appeals.filters.${s}`) }}
          <span v-if="s === 'open' && openCount" class="ml-1 text-lamp-300 tabular-nums">{{ openCount }}</span>
        </button>
      </div>
      <p class="text-xs text-base-400">{{ t('team.appeals.lead') }}</p>
      <button class="btn btn-ghost ml-auto px-3 py-1.5 text-xs" @click="load()">{{ t('common.actions.refresh') }}</button>
    </div>

    <div v-if="!appeals" class="space-y-2"><div v-for="i in 3" :key="i" class="skeleton h-32" /></div>
    <RedstoneEmpty v-else-if="!appeals.length" :title="status === 'open' ? t('team.appeals.noneOpen') : t('team.common.empty')" compact :seed="0x4b" />
    <ul v-else class="space-y-3">
      <li v-for="(a, i) in appeals" :key="a.id" :data-row="i">
        <article class="adm-row card p-4" :class="{ 'adm-row-active': active === i }" data-testid="appeal-row">
          <header class="flex flex-wrap items-center gap-2">
            <NuxtLink :to="`/admin/players/${a.sanction.player.uuid}`" class="flex items-center gap-2 font-semibold text-base-50 hover:text-redstone-300">
              <span class="block size-6 overflow-hidden rounded"><PlayerFace :uuid="a.sanction.player.uuid" :name="a.sanction.player.name ?? '?'" /></span>
              {{ a.sanction.player.name ?? a.sanction.player.uuid }}
            </NuxtLink>
            <SanctionKindBadge :kind="a.sanction.kind" />
            <span class="text-xs text-base-400">{{ reasonLabel(a.sanction.reasonCode) }}</span>
            <span class="badge" :class="a.status === 'open' ? 'bg-lamp-900 text-lamp-300' : 'bg-base-800 text-base-200'">{{ t(`sanctions.appeal.status.${a.status}`) }}</span>
            <span class="ml-auto text-xs text-base-400" :title="formatDate(a.createdAt)">{{ formatRelative(a.createdAt) }}</span>
          </header>
          <p class="mt-1 text-xs text-base-400">
            {{ t('team.appeals.sanctionLine', { id: a.sanction.id, end: a.sanction.endsAt ? formatDate(a.sanction.endsAt) : t('team.durations.permanent') }) }}
            <span v-if="a.sanction.reason"> · „{{ a.sanction.reason }}“</span>
          </p>
          <blockquote class="mt-3 rounded-lg border-l-2 border-redstone-500 bg-base-850 px-3 py-2 text-sm whitespace-pre-wrap text-base-50">{{ a.text }}</blockquote>
          <p v-if="a.response" class="mt-2 text-xs whitespace-pre-wrap text-base-200">
            <span class="text-base-400">{{ t('team.sanction.answer', { name: a.decidedBy?.name ?? '–' }) }}</span> {{ a.response }}
          </p>
          <footer v-if="a.status === 'open'" class="mt-3 flex flex-wrap items-center justify-end gap-2">
            <span v-if="own(a)" class="mr-auto text-[11px] text-base-400">{{ t('team.appeals.own') }}</span>
            <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="own(a)" @click="decide(a, 'uphold')">{{ t('team.appeals.decisions.uphold') }}</button>
            <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="own(a)" @click="decide(a, 'shorten')">{{ t('team.appeals.decisions.shorten') }}</button>
            <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="own(a)" data-testid="appeal-open" @click="decide(a, 'lift')">{{ t('team.appeals.decisions.lift') }}</button>
          </footer>
        </article>
      </li>
    </ul>
    <button v-if="cursor" class="btn btn-ghost mt-3" :disabled="loadingMore" @click="load(true)">{{ t('admin.mod.more') }}</button>

    <AdminAppealDialog v-if="deciding" :appeal="deciding.appeal" :decision="deciding.decision" @close="deciding = null" @decided="decided" />
  </section>
</template>
