<script setup lang="ts">
import type { AuditEntry } from '~/utils/moderation'

// Audit-Log: jede Team-Aktion mit wer/was/wann. Filter nach Bereich (Präfix),
// Handelndem und Zeitraum; ältere Einträge seitenweise.
const toasts = useToasts()
const trs = useTrsStore()
const groups = ['all', 'sanction', 'chat', 'user', 'report', 'appeal', 'role', 'cape', 'cosmetic', 'codes', 'player', 'hosting'] as const
const group = ref<(typeof groups)[number]>('all')
const actor = ref<'all' | 'me' | 'system' | 'api-key'>('all')
const from = ref('')
const entries = ref<AuditEntry[] | null>(null)
const before = ref<number | null>(null)
const loadingMore = ref(false)

async function load(more = false) {
  if (more) loadingMore.value = true
  try {
    const page = await backend.social.adminAudit({
      limit: 100,
      action: group.value === 'all' ? undefined : `${group.value}.`,
      actor: actor.value === 'all' ? undefined : actor.value === 'me' ? trs.me?.uuid : actor.value,
      from: from.value ? new Date(`${from.value}T00:00:00`).toISOString() : undefined,
      before: more ? (before.value ?? undefined) : undefined,
    })
    entries.value = more ? [...(entries.value ?? []), ...page.entries] : page.entries
    before.value = page.nextBefore
  } catch (e) {
    if (!more) entries.value = []
    toasts.error(e)
  } finally {
    loadingMore.value = false
  }
}
onMounted(() => void load())
watch([group, actor, from], () => {
  entries.value = null
  void load()
})
const items = computed(() => entries.value ?? [])
const { active } = useListKeys(items)
</script>

<template>
  <section :aria-label="t('team.nav.audit')" data-testid="admin-audit">
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <select v-model="group" class="field w-auto py-1.5 text-xs" :aria-label="t('team.audit.area')">
        <option v-for="g in groups" :key="g" :value="g">{{ t(`team.audit.groups.${g}`) }}</option>
      </select>
      <select v-model="actor" class="field w-auto py-1.5 text-xs" :aria-label="t('team.sanction.by')">
        <option value="all">{{ t('team.sanctions.anyActor') }}</option>
        <option value="me">{{ t('team.sanctions.byMe') }}</option>
        <option value="system">{{ t('team.common.system') }}</option>
        <option value="api-key">{{ t('team.common.apiKey') }}</option>
      </select>
      <label class="flex items-center gap-2 text-xs text-base-400">
        {{ t('team.audit.from') }}
        <input v-model="from" type="date" class="field w-auto py-1 text-xs" />
      </label>
      <button class="btn btn-ghost ml-auto px-3 py-1.5 text-xs" @click="load()">{{ t('common.actions.refresh') }}</button>
    </div>
    <div v-if="!entries" class="skeleton h-64" />
    <p v-else-if="!entries.length" class="card px-4 py-6 text-center text-sm text-base-400">{{ t('admin.mod.noAudit') }}</p>
    <ul v-else class="card divide-y divide-base-800/60">
      <li v-for="(e, i) in entries" :key="e.id" :data-row="i" class="px-4 py-2" :class="{ 'adm-row-active': active === i }">
        <AdminAuditLine :entry="e" />
      </li>
    </ul>
    <button v-if="before" class="btn btn-ghost mt-3" :disabled="loadingMore" @click="load(true)">{{ t('admin.mod.more') }}</button>
  </section>
</template>
