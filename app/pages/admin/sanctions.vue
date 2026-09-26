<script setup lang="ts">
import { sanctionKinds, type SanctionKind } from '~/utils/sanctions'
import type { AdminSanction, SanctionQuery } from '~/utils/team'

// Alle Strafen: Status (aktiv/abgelaufen/aufgehoben/alle), Art, Vergeben von
// (System, API-Schlüssel, ich), Sortierung. Aufheben und Ende ändern direkt hier.
const toasts = useToasts()
const route = useRoute()
const trs = useTrsStore()
const status = ref<NonNullable<SanctionQuery['status']>>('active')
const kind = ref<'all' | SanctionKind>((sanctionKinds as readonly string[]).includes(String(route.query.kind)) ? (route.query.kind as SanctionKind) : 'all')
const actor = ref<'all' | 'me' | 'system' | 'api-key'>('all')
const sort = ref<'newest' | 'oldest'>('newest')
const list = ref<AdminSanction[] | null>(null)
const cursor = ref<string | null>(null)
const loadingMore = ref(false)
const changing = ref<{ sanction: AdminSanction; mode: 'lift' | 'change' } | null>(null)
const items = computed(() => list.value ?? [])

async function load(more = false) {
  if (more) loadingMore.value = true
  try {
    const page = await backend.team.sanctions({
      status: status.value,
      kind: kind.value === 'all' ? undefined : kind.value,
      actor: actor.value === 'all' ? undefined : actor.value === 'me' ? trs.me?.uuid : actor.value,
      sort: sort.value,
      cursor: more ? (cursor.value ?? undefined) : undefined,
      limit: 30,
    })
    list.value = more ? [...(list.value ?? []), ...page.sanctions] : page.sanctions
    cursor.value = page.nextCursor
  } catch (e) {
    if (!more) list.value = []
    toasts.error(e)
  } finally {
    loadingMore.value = false
  }
}
onMounted(() => void load())
watch([status, kind, actor, sort], () => {
  list.value = null
  void load()
})

function changed() {
  changing.value = null
  toasts.ok(t('team.change.done'))
  void load()
}
const { active } = useListKeys(items)
</script>

<template>
  <section :aria-label="t('team.nav.sanctions')" data-testid="admin-sanctions">
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs">
        <button v-for="s in (['active', 'expired', 'lifted', 'all'] as const)" :key="s" class="seg rounded-md" :class="{ 'seg-on': status === s }" :aria-pressed="status === s" @click="status = s">
          {{ t(`team.status.${s}`) }}
        </button>
      </div>
      <select v-model="kind" class="field w-auto py-1.5 text-xs" :aria-label="t('team.form.kind')">
        <option value="all">{{ t('team.sanctions.allKinds') }}</option>
        <option v-for="k in sanctionKinds" :key="k" :value="k">{{ t(`sanctions.kinds.${k}`) }}</option>
      </select>
      <select v-model="actor" class="field w-auto py-1.5 text-xs" :aria-label="t('team.sanction.by')">
        <option value="all">{{ t('team.sanctions.anyActor') }}</option>
        <option value="me">{{ t('team.sanctions.byMe') }}</option>
        <option value="system">{{ t('team.common.system') }}</option>
        <option value="api-key">{{ t('team.common.apiKey') }}</option>
      </select>
      <select v-model="sort" class="field ml-auto w-auto py-1.5 text-xs" :aria-label="t('team.common.sort')">
        <option value="newest">{{ t('team.common.newest') }}</option>
        <option value="oldest">{{ t('team.common.oldest') }}</option>
      </select>
    </div>

    <div v-if="!list" class="space-y-2"><div v-for="i in 3" :key="i" class="skeleton h-32" /></div>
    <RedstoneEmpty v-else-if="!list.length" :title="t('team.sanctions.none')" compact :seed="0x6d" />
    <div v-else class="grid gap-3 xl:grid-cols-2">
      <div v-for="(s, i) in list" :key="s.id" :data-row="i" :class="{ 'rounded-xl ring-2 ring-redstone-500/60': active === i }">
        <AdminSanctionCard :sanction="s" show-player @lift="changing = { sanction: s, mode: 'lift' }" @change="changing = { sanction: s, mode: 'change' }" />
      </div>
    </div>
    <button v-if="cursor" class="btn btn-ghost mt-3" :disabled="loadingMore" @click="load(true)">{{ t('admin.mod.more') }}</button>

    <AdminSanctionChangeDialog v-if="changing" :sanction="changing.sanction" :mode="changing.mode" @close="changing = null" @changed="changed" />
  </section>
</template>
