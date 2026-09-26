<script setup lang="ts">
import type { TrsAdminCape, TrsReportReason, TrsReviewList } from '~/utils/trs'
import type { AdminCosmetic } from '~/utils/team'

// Umhänge und Kosmetik prüfen: Listen (wartend, gemeldet, freigegeben,
// abgelehnt), Namenssuche, Mehrfachauswahl mit Freigeben/Ablehnen (höchstens
// 50), Tasten j/k, Enter (Detail), a (freigeben), r (ablehnen), x (auswählen).
// Löschen dürfen nur Admins.
const toasts = useToasts()
const team = useTeam()
const route = useRoute()

const kind = ref<'capes' | 'cosmetics'>(route.query.kind === 'cosmetics' ? 'cosmetics' : 'capes')
const list = ref<TrsReviewList>('pending')
const lists: TrsReviewList[] = ['pending', 'reported', 'approved', 'rejected']
const q = ref('')
const capes = ref<TrsAdminCape[] | null>(null)
const cosmetics = ref<AdminCosmetic[] | null>(null)
const reviewing = ref<string | null>(null)
const { selected, toggle, setAll, clear, limit } = useSelection()
const bulk = ref<{ action: 'approve' | 'reject'; ids: string[] } | null>(null)
const bulkBusy = ref(false)
const deleting = ref<AdminCosmetic | null>(null)

const items = computed<{ id: string; status: string }[]>(() => (kind.value === 'capes' ? (capes.value ?? []) : (cosmetics.value ?? [])))
const reportReasons: readonly string[] = ['inappropriate', 'copyright', 'impersonation', 'other'] satisfies TrsReportReason[]
function reportSummary(reasons: Record<string, number>): string {
  return Object.entries(reasons)
    .map(([r, n]) => `${reportReasons.includes(r) ? t(`admin.review.reasons.${r as TrsReportReason}`) : r} (${n})`)
    .join(', ')
}

async function load() {
  const query = { status: list.value, q: q.value.trim() || undefined }
  try {
    if (kind.value === 'capes') capes.value = await backend.team.capes(query)
    else cosmetics.value = (await backend.team.cosmetics(query)).cosmetics
  } catch (e) {
    if (kind.value === 'capes') capes.value = []
    else cosmetics.value = []
    toasts.error(e)
  }
}
async function reload() {
  await load()
  void team.refreshCounts()
}

let timer: ReturnType<typeof setTimeout> | null = null
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), 300)
})
watch([kind, list], () => {
  capes.value = null
  cosmetics.value = null
  clear()
  void load()
})
onMounted(load)

function ask(action: 'approve' | 'reject', ids = [...selected.value]) {
  if (ids.length) bulk.value = { action, ids }
}
async function runBulk(reason: string) {
  const b = bulk.value
  if (!b) return
  bulkBusy.value = true
  try {
    const r = await backend.team.bulk(kind.value, { ids: b.ids, action: b.action, ...(b.action === 'reject' && reason ? { reason } : {}) })
    toasts.ok(t('team.bulk.done', { updated: r.updated.length, skipped: r.skipped.length }))
    bulk.value = null
    clear()
    await reload()
  } catch (e) {
    toasts.error(e)
  } finally {
    bulkBusy.value = false
  }
}

async function deleteCosmetic() {
  const c = deleting.value
  if (!c) return
  bulkBusy.value = true
  try {
    await backend.team.deleteCosmetic(c.id)
    toasts.ok(t('team.uploads.deleted', { name: c.name }))
    deleting.value = null
    await reload()
  } catch (e) {
    toasts.error(e)
  } finally {
    bulkBusy.value = false
  }
}

const { active } = useListKeys(items, {
  open: (it) => kind.value === 'capes' && (reviewing.value = it.id),
  onA: (it) => it.status !== 'approved' && ask('approve', [it.id]),
  onR: (it) => it.status !== 'rejected' && ask('reject', [it.id]),
  toggle: (it) => toggle(it.id),
})
</script>

<template>
  <section :aria-label="t('team.nav.uploads')" data-testid="admin-uploads">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs">
        <button class="seg rounded-md" :class="{ 'seg-on': kind === 'capes' }" :aria-pressed="kind === 'capes'" @click="kind = 'capes'">{{ t('team.uploads.capes') }}</button>
        <button class="seg rounded-md" :class="{ 'seg-on': kind === 'cosmetics' }" :aria-pressed="kind === 'cosmetics'" @click="kind = 'cosmetics'">{{ t('team.uploads.cosmetics') }}</button>
      </div>
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="key in lists"
          :key="key"
          class="chip"
          :class="{ 'bg-redstone-900/60 text-base-50 ring-1 ring-redstone-500': list === key }"
          :aria-pressed="list === key"
          @click="list = key"
        >
          {{ t(`admin.review.lists.${key}`) }}
        </button>
      </div>
      <input v-model="q" class="field ml-auto w-56 py-1.5 text-xs" maxlength="32" :placeholder="t('team.uploads.search')" :aria-label="t('team.uploads.search')" />
    </div>
    <label v-if="items.length" class="mb-3 flex items-center gap-2 text-xs text-base-400">
      <input
        type="checkbox"
        class="accent-redstone-500"
        :checked="selected.size > 0 && selected.size >= Math.min(limit, items.length)"
        @change="setAll(items.map((i) => i.id), ($event.target as HTMLInputElement).checked)"
      />
      {{ t('team.common.selectAll') }}
    </label>

    <!-- Umhänge -->
    <template v-if="kind === 'capes'">
      <div v-if="!capes" class="space-y-2"><div v-for="i in 3" :key="i" class="skeleton h-24" /></div>
      <RedstoneEmpty v-else-if="!capes.length" :title="list === 'pending' ? t('admin.review.emptyPending') : t('admin.review.empty')" compact :seed="0x5c" />
      <ul v-else class="grid gap-2 xl:grid-cols-2" data-testid="admin-capes">
        <li v-for="(c, i) in capes" :key="c.id" :data-row="i">
          <div class="adm-row card flex items-center gap-3 px-3 py-3" :class="{ 'adm-row-active': active === i, 'adm-row-selected': selected.has(c.id) }">
            <input type="checkbox" class="accent-redstone-500" :checked="selected.has(c.id)" :aria-label="c.name" @change="toggle(c.id)" />
            <button class="flex min-w-0 flex-1 items-center gap-3 text-left" data-testid="admin-cape-open" @click="reviewing = c.id">
              <span class="rounded-lg bg-base-850 p-2"><CapeThumb :texture="c.texture" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="36" /></span>
              <span class="min-w-0 flex-1 text-sm">
                <span class="flex items-center gap-2">
                  <span class="truncate font-semibold text-base-50">{{ c.name }}</span>
                  <span v-if="c.frames > 1" class="badge bg-base-800 px-1.5 py-0 text-[10px] text-base-200">{{ t('capes.animated') }}</span>
                </span>
                <span class="block truncate text-xs text-base-400">{{ c.owner?.name ?? t('admin.review.deletedAccount') }} · {{ formatRelative(c.createdAt) }} · {{ c.width }}×{{ c.height }}</span>
                <span v-if="c.reports.count" class="block truncate text-xs text-lamp-300">{{ t('admin.review.reported', { count: c.reports.count, reasons: reportSummary(c.reports.reasons) }) }}</span>
                <span v-if="c.rejectReason" class="block truncate text-xs text-redstone-300">{{ t('admin.review.rejectReason', { reason: c.rejectReason }) }}</span>
              </span>
            </button>
            <div class="flex shrink-0 flex-col gap-1.5">
              <button v-if="c.status !== 'approved' || c.reports.count" class="btn btn-primary px-2.5 py-1 text-xs" @click="ask('approve', [c.id])">{{ t('admin.review.approve') }}</button>
              <button v-if="c.status !== 'rejected'" class="btn btn-ghost px-2.5 py-1 text-xs" @click="ask('reject', [c.id])">{{ t('admin.review.reject') }}</button>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <!-- Kosmetik -->
    <template v-else>
      <div v-if="!cosmetics" class="space-y-2"><div v-for="i in 3" :key="i" class="skeleton h-24" /></div>
      <RedstoneEmpty v-else-if="!cosmetics.length" :title="list === 'pending' ? t('team.uploads.emptyCosmetics') : t('admin.review.empty')" compact :seed="0x5d" />
      <ul v-else class="grid gap-2 xl:grid-cols-2" data-testid="admin-cosmetics">
        <li v-for="(c, i) in cosmetics" :key="c.id" :data-row="i">
          <div class="adm-row card flex items-center gap-3 px-3 py-3" :class="{ 'adm-row-active': active === i, 'adm-row-selected': selected.has(c.id) }">
            <input type="checkbox" class="accent-redstone-500" :checked="selected.has(c.id)" :aria-label="c.name" @change="toggle(c.id)" />
            <span class="grid size-16 shrink-0 place-items-center overflow-hidden rounded-lg bg-base-850">
              <img v-if="c.preview" :src="c.preview" alt="" class="max-h-14 max-w-14 [image-rendering:pixelated]" />
              <span v-else class="text-[10px] text-base-400">{{ c.slot }}</span>
            </span>
            <span class="min-w-0 flex-1 text-sm">
              <span class="block truncate font-semibold text-base-50">{{ c.name }}</span>
              <span class="block truncate text-xs text-base-400">{{ c.slot }} · {{ c.owner?.name || t('admin.review.deletedAccount') }} · {{ formatRelative(c.createdAt) }}</span>
              <span v-if="c.reports.count" class="block text-xs text-lamp-300">{{ t('admin.review.reported', { count: c.reports.count, reasons: reportSummary(c.reports.reasons) }) }}</span>
              <span v-if="c.rejectReason" class="block truncate text-xs text-redstone-300">{{ t('admin.review.rejectReason', { reason: c.rejectReason }) }}</span>
              <NuxtLink v-if="c.owner" :to="`/admin/players/${c.owner.uuid}`" class="text-xs text-redstone-300 hover:underline">{{ t('team.reports.openFile') }}</NuxtLink>
            </span>
            <div class="flex shrink-0 flex-col gap-1.5">
              <button v-if="c.status !== 'approved' || c.reports.count" class="btn btn-primary px-2.5 py-1 text-xs" @click="ask('approve', [c.id])">{{ t('admin.review.approve') }}</button>
              <button v-if="c.status !== 'rejected'" class="btn btn-ghost px-2.5 py-1 text-xs" @click="ask('reject', [c.id])">{{ t('admin.review.reject') }}</button>
              <button v-if="team.isAdmin.value" class="btn btn-ghost px-2.5 py-1 text-xs hover:text-redstone-300" @click="deleting = c">{{ t('common.actions.delete') }}</button>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <AdminBulkBar :count="selected.size" :limit="limit" @clear="clear">
      <button class="btn btn-ghost" @click="ask('reject')">{{ t('team.uploads.rejectSelected') }}</button>
      <button class="btn btn-primary" @click="ask('approve')"><SocialIcon name="check" class="size-4" />{{ t('team.uploads.approveSelected') }}</button>
    </AdminBulkBar>

    <TrsCapeReviewDialog v-if="reviewing && capes" :capes="capes" :start-id="reviewing" :reload="reload" @close="reviewing = null" />
    <AdminConfirm
      v-if="bulk"
      :title="bulk.action === 'approve' ? t('team.uploads.approveSelected') : t('team.uploads.rejectSelected')"
      :text="t(`team.uploads.confirm.${bulk.action}`, { n: bulk.ids.length })"
      :confirm-label="bulk.action === 'approve' ? t('admin.review.approve') : t('admin.review.reject')"
      :danger="bulk.action === 'reject'"
      :busy="bulkBusy"
      :reason="bulk.action === 'reject' ? { label: t('admin.dialogs.rejectReasonLabel'), max: 200, placeholder: t('admin.dialogs.rejectPlaceholder') } : undefined"
      @confirm="runBulk"
      @close="bulk = null"
    />
    <AdminConfirm
      v-if="deleting"
      :title="t('team.uploads.deleteTitle')"
      :text="t('team.uploads.deleteText', { name: deleting.name })"
      :confirm-label="t('common.actions.delete')"
      danger
      :busy="bulkBusy"
      @confirm="deleteCosmetic"
      @close="deleting = null"
    />
  </section>
</template>
