<script setup lang="ts">
import type { TrsAdminCape, TrsAdminStats, TrsAdminUser, TrsCape, TrsCode, TrsReportReason, TrsReviewList } from '~/utils/trs'

// Verwaltung der TRS-Dienste – nur für Admins (der Server prüft das bei jeder
// Anfrage selbst; die Seite blendet sich für alle anderen nur aus).
const trs = useTrsStore()
const toasts = useToasts()

type Tab = 'overview' | 'capes' | 'codes' | 'players'
const tab = ref<Tab>('overview')
const tabs: Tab[] = ['overview', 'capes', 'codes', 'players']
const busy = ref<string | null>(null)

const stats = ref<TrsAdminStats | null>(null)
const catalog = ref<TrsCape[]>([])
/** Umhänge, die man per Code oder Vergabe bekommt (freie brauchen das nicht). */
const grantable = computed(() => catalog.value.filter((c) => c.kind === 'builtin' && c.unlock !== 'free'))

async function run(key: string, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = key
  try {
    await action()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

async function loadStats() {
  try {
    stats.value = await backend.trs.adminStats()
  } catch (e) {
    toasts.error(e)
  }
}

async function loadCatalog() {
  try {
    catalog.value = await backend.trs.capes()
  } catch {
    catalog.value = []
  }
}

// --- Umhänge prüfen -----------------------------------------------------------------

const list = ref<TrsReviewList>('pending')
const reviewCapes = ref<TrsAdminCape[] | null>(null)
const lists: TrsReviewList[] = ['pending', 'reported', 'approved', 'rejected']
const reportReasons: readonly string[] = ['inappropriate', 'copyright', 'impersonation', 'other'] satisfies TrsReportReason[]

/** Meldegründe mit Anzahl („unangemessen (2), Urheberrecht (1)“); unbekannte Gründe roh. */
function reportSummary(reasons: Record<string, number>): string {
  return Object.entries(reasons)
    .map(([r, n]) => `${reportReasons.includes(r) ? t(`admin.review.reasons.${r as TrsReportReason}`) : r} (${n})`)
    .join(', ')
}

/** Detail-Dialog: ID des geöffneten Umhangs. */
const reviewing = ref<string | null>(null)

/** Neu laden ohne Ladeanzeige (der Detail-Dialog bleibt offen). */
async function refreshReview() {
  try {
    reviewCapes.value = await backend.trs.adminCapes(list.value)
  } catch (e) {
    toasts.error(e)
  }
  await loadStats()
}

async function loadReview() {
  reviewCapes.value = null
  try {
    reviewCapes.value = await backend.trs.adminCapes(list.value)
  } catch (e) {
    reviewCapes.value = []
    toasts.error(e)
  }
}

const approve = (cape: TrsAdminCape) =>
  run(`approve:${cape.id}`, async () => {
    await backend.trs.adminApprove(cape.id)
    toasts.ok(t('admin.toasts.approved', { name: cape.name }))
    await Promise.all([loadReview(), loadStats()])
  })

const rejecting = ref<TrsAdminCape | null>(null)
const rejectReason = ref('')
const rejectError = ref<string | null>(null)
function startReject(cape: TrsAdminCape) {
  rejecting.value = cape
  rejectReason.value = ''
  rejectError.value = null
}
async function confirmReject() {
  const cape = rejecting.value
  if (!cape) return
  const reason = trsNoteSchema.safeParse(rejectReason.value)
  if (!reason.success) {
    rejectError.value = firstIssue(reason.error)
    return
  }
  rejecting.value = null
  await run(`reject:${cape.id}`, async () => {
    await backend.trs.adminReject(cape.id, reason.data || null)
    toasts.ok(t('admin.toasts.rejected', { name: cape.name }))
    await Promise.all([loadReview(), loadStats()])
  })
}

const deleting = ref<TrsAdminCape | null>(null)
async function confirmDeleteCape() {
  const cape = deleting.value
  deleting.value = null
  if (!cape) return
  await run(`delete:${cape.id}`, async () => {
    await backend.trs.adminDeleteCape(cape.id)
    toasts.ok(t('admin.toasts.capeDeleted'))
    await Promise.all([loadReview(), loadStats()])
  })
}

// --- Codes --------------------------------------------------------------------------

const codes = ref<TrsCode[] | null>(null)
const created = ref<TrsCode[]>([])
const codeForm = reactive({ capeId: '', maxUses: 1, count: 1, expires: '', note: '' })
const codeError = ref<string | null>(null)

async function loadCodes() {
  try {
    codes.value = await backend.trs.adminCodes()
  } catch (e) {
    codes.value = []
    toasts.error(e)
  }
}

async function createCodes() {
  // Ablaufdatum aus dem Datumsfeld: Ende des Tages (Ortszeit).
  const expiresAt = codeForm.expires ? new Date(`${codeForm.expires}T23:59:59`).toISOString() : null
  const parsed = trsNewCodesSchema.safeParse({
    capeId: codeForm.capeId,
    maxUses: Number(codeForm.maxUses),
    count: Number(codeForm.count),
    expiresAt,
    note: codeForm.note.trim() || null,
  })
  if (!parsed.success) {
    codeError.value = codeForm.capeId ? firstIssue(parsed.error) : t('admin.codes.pickCape')
    return
  }
  codeError.value = null
  await run('codes', async () => {
    const { capeId, maxUses, count, expiresAt: at, note } = parsed.data
    created.value = await backend.trs.adminCreateCodes({
      capeId,
      maxUses,
      count,
      ...(at ? { expiresAt: at } : {}),
      ...(note ? { note } : {}),
    })
    toasts.ok(t('admin.toasts.codesCreated', created.value.length))
    await Promise.all([loadCodes(), loadStats()])
  })
}

async function copy(text: string, what: 'code' | 'all' | 'uuid' = 'code') {
  try {
    await navigator.clipboard.writeText(text)
    toasts.ok(t(`admin.toasts.copied.${what}`))
  } catch {
    toasts.error(t('admin.toasts.copyFailed'))
  }
}

const revoking = ref<TrsCode | null>(null)
async function confirmRevoke() {
  const code = revoking.value
  revoking.value = null
  if (!code) return
  await run(`revoke:${code.id}`, async () => {
    await backend.trs.adminRevokeCode(code.id)
    toasts.ok(t('admin.toasts.codeRevoked'))
    await loadCodes()
  })
}

function capeName(id: string) {
  return catalog.value.find((c) => c.id === id)?.name ?? id
}

// --- Spieler ------------------------------------------------------------------------

const query = ref('')
const queryError = ref<string | null>(null)
const player = ref<TrsAdminUser | null>(null)
const grantCape = ref('')
const banReason = ref('')
const banning = ref(false)

async function lookup() {
  const parsed = trsTargetSchema.safeParse(query.value)
  if (!parsed.success) {
    queryError.value = firstIssue(parsed.error)
    return
  }
  queryError.value = null
  await run('lookup', async () => {
    try {
      player.value = await backend.trs.adminUser(parsed.data)
    } catch (e) {
      player.value = null
      queryError.value = errorMessage(e)
    }
  })
}

async function reloadPlayer() {
  if (player.value) player.value = await backend.trs.adminUser(player.value.uuid)
}

async function grant() {
  const p = player.value
  if (!p || !grantCape.value) return
  await run('grant', async () => {
    const already = await backend.trs.adminGrant(p.uuid, grantCape.value)
    toasts.ok(
      already
        ? t('admin.toasts.alreadyHad')
        : t('admin.toasts.granted', { cape: capeName(grantCape.value), player: p.name ?? t('admin.toasts.playerFallback') }),
    )
    await reloadPlayer()
  })
}

const revokeGrant = (capeId: string) =>
  run(`ungrant:${capeId}`, async () => {
    const p = player.value
    if (!p) return
    await backend.trs.adminRevokeGrant(p.uuid, capeId)
    toasts.ok(t('admin.toasts.grantRevoked'))
    await reloadPlayer()
  })

async function confirmBan() {
  const p = player.value
  banning.value = false
  if (!p) return
  const reason = trsNoteSchema.safeParse(banReason.value)
  if (!reason.success) {
    toasts.error(firstIssue(reason.error))
    return
  }
  await run('ban', async () => {
    player.value = await backend.trs.adminBan(p.uuid, reason.data || null)
    banReason.value = ''
    toasts.ok(t('admin.toasts.banned'))
    await loadStats()
  })
}

const unban = () =>
  run('unban', async () => {
    const p = player.value
    if (!p) return
    await backend.trs.adminUnban(p.uuid)
    toasts.ok(t('admin.toasts.unbanned'))
    await reloadPlayer()
    await loadStats()
  })

// --- Laden --------------------------------------------------------------------------

async function init() {
  if (!trs.isAdmin) return
  await Promise.all([loadStats(), loadCatalog()])
}
onMounted(async () => {
  if (!trs.status) await trs.init()
  await init()
})
watch(() => trs.isAdmin, init)
watch(tab, (value) => {
  if (value === 'capes' && !reviewCapes.value) void loadReview()
  if (value === 'codes' && !codes.value) void loadCodes()
  if (value === 'overview') void loadStats()
})
watch(list, loadReview)

const statTiles = computed(() => {
  const s = stats.value
  if (!s) return []
  const count = (n: number) => ({ count: formatNumber(n) })
  return [
    { id: 'users', label: t('admin.stats.users'), value: s.users.total, hint: t('admin.stats.usersHint', count(s.users.activeLast24h)) },
    { id: 'online', label: t('admin.stats.online'), value: s.users.online, hint: t('admin.stats.onlineHint', count(s.eventStreams)) },
    {
      id: 'pending',
      label: t('admin.stats.pending'),
      value: s.capes.pending,
      hint: t('admin.stats.pendingHint', count(s.capes.reported)),
      alert: s.capes.pending > 0,
    },
    { id: 'worn', label: t('admin.stats.worn'), value: s.capes.activeUsers, hint: t('admin.stats.wornHint', count(s.capes.approved)) },
    { id: 'codes', label: t('admin.stats.codes'), value: s.codes.active, hint: t('admin.stats.codesHint', count(s.codes.redemptions)) },
    {
      id: 'friendships',
      label: t('admin.stats.friendships'),
      value: s.friendships,
      hint: t('admin.stats.friendshipsHint', count(s.pendingFriendRequests)),
    },
    { id: 'banned', label: t('admin.stats.banned'), value: s.users.banned, hint: t('admin.stats.bannedHint', count(s.sessions)) },
  ]
})
</script>

<template>
  <div class="mx-auto max-w-5xl p-6">
    <PageHeader :title="t('admin.title')" :subtitle="t('admin.subtitle')">
      <button v-if="trs.isAdmin" class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="admin-web-login" @click="trs.openWebLogin()">
        {{ t('webLogin.title') }}
      </button>
    </PageHeader>

    <div v-if="!trs.isAdmin" class="card px-4 py-6 text-center text-sm text-base-400">
      {{ t('admin.notAdmin') }}
    </div>

    <template v-else>
      <div class="mb-5 flex gap-1 rounded-lg bg-base-850 p-1 text-xs" role="tablist" :aria-label="t('admin.tabs.label')">
        <button
          v-for="key in tabs"
          :key="key"
          class="seg flex flex-1 items-center justify-center gap-1.5 rounded-md"
          :class="{ 'seg-on': tab === key }"
          role="tab"
          :aria-selected="tab === key"
          @click="tab = key"
        >
          {{ t(`admin.tabs.${key}`) }}
          <span v-if="key === 'capes' && stats?.capes.pending" class="rounded-full bg-redstone-500 px-1.5 text-[10px] font-bold text-white">
            {{ stats.capes.pending }}
          </span>
        </button>
      </div>

      <!-- Übersicht --------------------------------------------------------------- -->
      <section v-if="tab === 'overview'" :aria-label="t('admin.tabs.overview')">
        <div v-if="!stats" class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <div v-for="i in 7" :key="i" class="skeleton h-24" />
        </div>
        <div v-else class="grid grid-cols-2 gap-3 md:grid-cols-4" data-testid="admin-stats">
          <div v-for="tile in statTiles" :key="tile.id" class="card px-4 py-3" :class="{ 'border-lamp-400/40': tile.alert }">
            <p class="text-xs text-base-400">{{ tile.label }}</p>
            <p class="display mt-1 text-3xl tabular-nums" :class="tile.alert ? 'text-lamp-300' : 'text-base-50'">{{ formatNumber(tile.value) }}</p>
            <p class="mt-0.5 text-[11px] text-base-600">{{ tile.hint }}</p>
          </div>
        </div>
        <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="loadStats">{{ t('common.actions.refresh') }}</button>
      </section>

      <!-- Umhänge prüfen ---------------------------------------------------------- -->
      <section v-else-if="tab === 'capes'" :aria-label="t('admin.tabs.capes')">
        <div class="mb-3 flex flex-wrap gap-1.5">
          <button
            v-for="key in lists"
            :key="key"
            class="chip"
            :class="{ 'border-redstone-500 bg-redstone-900/40 text-base-50': list === key }"
            :aria-pressed="list === key"
            @click="list = key"
          >
            {{ t(`admin.review.lists.${key}`) }}
          </button>
        </div>
        <div v-if="!reviewCapes" class="space-y-2">
          <div v-for="i in 3" :key="i" class="skeleton h-28" />
        </div>
        <RedstoneEmpty
          v-else-if="!reviewCapes.length"
          :title="list === 'pending' ? t('admin.review.emptyPending') : t('admin.review.empty')"
          compact
          :seed="0x5c"
        />
        <ul v-else class="space-y-2" data-testid="admin-capes">
          <li v-for="cape in reviewCapes" :key="cape.id" class="card flex flex-wrap items-center gap-4 px-4 py-3">
            <button
              class="flex min-w-0 flex-1 items-center gap-4 rounded-lg text-left hover:bg-base-800/40 focus-visible:ring-2 focus-visible:ring-redstone-500 focus-visible:outline-none"
              :title="t('admin.review.dialog.open')"
              data-testid="admin-cape-open"
              @click="reviewing = cape.id"
            >
              <span class="flex items-end gap-3 rounded-lg bg-base-850 p-2">
                <CapeThumb :texture="cape.texture" :scale="cape.scale" :frames="cape.frames" :frame-time-ms="cape.frameTimeMs" :width="40" />
                <img
                  v-if="cape.texture"
                  :src="cape.texture"
                  :alt="t('admin.review.fullTexture')"
                  class="h-16 w-32 rounded object-contain object-top [image-rendering:pixelated]"
                />
              </span>
              <span class="min-w-0 flex-1 text-sm">
                <span class="flex items-center gap-2">
                  <span class="truncate font-semibold text-base-50">{{ cape.name }}</span>
                  <span v-if="cape.frames > 1" class="badge bg-base-800 px-1.5 py-0 text-[10px] text-base-200">{{ t('capes.animated') }}</span>
                </span>
                <i18n-t keypath="admin.review.byOwner" tag="span" scope="global" class="block text-xs text-base-400">
                  <template #owner><strong class="text-base-200">{{ cape.owner?.name ?? t('admin.review.deletedAccount') }}</strong></template>
                  <template #date>{{ trsDate(cape.createdAt) }}</template>
                  <template #size>{{ cape.width }}×{{ cape.height }}</template>
                </i18n-t>
                <span v-if="cape.reports.count" class="mt-0.5 block text-xs text-warn">
                  {{ t('admin.review.reported', { count: cape.reports.count, reasons: reportSummary(cape.reports.reasons) }) }}
                </span>
                <span v-if="cape.rejectReason" class="mt-0.5 block text-xs text-redstone-300">{{ t('admin.review.rejectReason', { reason: cape.rejectReason }) }}</span>
                <span v-if="cape.reviewedBy" class="mt-0.5 block text-[11px] text-base-600">{{ t('admin.review.reviewed', { date: trsDate(cape.reviewedAt) }) }}</span>
              </span>
            </button>
            <div class="flex gap-2">
              <button
                v-if="cape.status !== 'approved' || cape.reports.count"
                class="btn btn-primary px-3 py-1.5 text-xs"
                :disabled="!!busy"
                @click="approve(cape)"
              >
                {{ t('admin.review.approve') }}
              </button>
              <button v-if="cape.status !== 'rejected'" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="startReject(cape)">
                {{ t('admin.review.reject') }}
              </button>
              <button class="btn btn-ghost px-3 py-1.5 text-xs hover:text-redstone-300" :disabled="!!busy" @click="deleting = cape">{{ t('common.actions.delete') }}</button>
            </div>
          </li>
        </ul>
      </section>

      <!-- Codes ------------------------------------------------------------------- -->
      <section v-else-if="tab === 'codes'" :aria-label="t('admin.tabs.codes')" class="space-y-4">
        <form class="card grid grid-cols-1 gap-3 p-4 md:grid-cols-[2fr_1fr_1fr_1.4fr]" @submit.prevent="createCodes">
          <label class="block">
            <span class="label">{{ t('admin.codes.cape') }}</span>
            <select v-model="codeForm.capeId" class="field">
              <option value="" disabled>{{ t('admin.codes.chooseCape') }}</option>
              <option v-for="c in grantable" :key="c.id" :value="c.id">{{ c.name }} ({{ trsUnlockLabel(c) }})</option>
            </select>
          </label>
          <label class="block">
            <span class="label">{{ t('admin.codes.usesPerCode') }}</span>
            <input v-model.number="codeForm.maxUses" type="number" min="1" max="100000" class="field" />
          </label>
          <label class="block">
            <span class="label">{{ t('admin.codes.count') }}</span>
            <input v-model.number="codeForm.count" type="number" min="1" max="100" class="field" />
          </label>
          <label class="block">
            <span class="label">{{ t('admin.codes.validUntil') }}</span>
            <input v-model="codeForm.expires" type="date" class="field" />
          </label>
          <label class="block md:col-span-3">
            <span class="label">{{ t('admin.codes.note') }}</span>
            <input v-model="codeForm.note" class="field" maxlength="200" :placeholder="t('admin.codes.notePlaceholder')" />
          </label>
          <div class="flex items-end">
            <button class="btn btn-primary w-full" :disabled="!!busy">{{ busy === 'codes' ? t('admin.codes.creating') : t('admin.codes.create') }}</button>
          </div>
          <p v-if="codeError" role="alert" class="text-xs text-redstone-300 md:col-span-4">{{ codeError }}</p>
        </form>

        <div v-if="created.length" class="card border-lamp-400/40 p-4" data-testid="admin-created-codes">
          <div class="mb-2 flex items-center gap-2">
            <p class="flex-1 text-sm font-semibold text-lamp-300">{{ t('admin.codes.newCodes') }}</p>
            <button class="btn btn-ghost px-3 py-1 text-xs" @click="copy(created.map((c) => c.code).join('\n'), 'all')">{{ t('admin.codes.copyAll') }}</button>
            <button class="btn btn-ghost px-3 py-1 text-xs" @click="created = []">{{ t('admin.codes.hide') }}</button>
          </div>
          <ul class="grid gap-1.5 sm:grid-cols-2">
            <li v-for="c in created" :key="c.id" class="flex items-center gap-2 rounded-md bg-base-850 px-3 py-1.5">
              <code class="flex-1 font-mono text-sm tracking-wider text-base-50 select-all">{{ c.code }}</code>
              <button class="btn btn-ghost px-2 py-0.5 text-[11px]" @click="copy(c.code ?? '')">{{ t('common.actions.copy') }}</button>
            </li>
          </ul>
        </div>

        <div v-if="!codes" class="skeleton h-40" />
        <RedstoneEmpty v-else-if="!codes.length" :title="t('admin.codes.empty')" compact :seed="0x19" />
        <div v-else class="card overflow-x-auto">
          <table class="w-full text-left text-xs">
            <thead class="text-base-400">
              <tr class="border-b border-base-800">
                <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.code') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.cape') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.redeemed') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.validUntil') }}</th>
                <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.note') }}</th>
                <th class="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in codes" :key="c.id" class="border-b border-base-800/60 last:border-0" :class="{ 'opacity-50': c.revokedAt }">
                <td class="px-3 py-2 font-mono text-base-200">…{{ c.hint }}</td>
                <td class="px-3 py-2">{{ capeName(c.capeId) }}</td>
                <td class="px-3 py-2 tabular-nums">{{ c.uses }} / {{ c.maxUses }}</td>
                <td class="px-3 py-2">{{ c.expiresAt ? trsDate(c.expiresAt) : t('admin.codes.unlimited') }}</td>
                <td class="max-w-48 truncate px-3 py-2 text-base-400" :title="c.note ?? ''">{{ c.note ?? '–' }}</td>
                <td class="px-3 py-2 text-right">
                  <span v-if="c.revokedAt" class="text-base-600">{{ t('admin.codes.revoked') }}</span>
                  <button v-else class="btn btn-ghost px-2 py-0.5 text-[11px] hover:text-redstone-300" :disabled="!!busy" @click="revoking = c">{{ t('admin.codes.revoke') }}</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Spieler ----------------------------------------------------------------- -->
      <section v-else :aria-label="t('admin.tabs.players')" class="space-y-4">
        <form class="card flex flex-wrap items-center gap-2 px-3 py-2.5" @submit.prevent="lookup">
          <input v-model="query" class="field min-w-0 flex-1 py-1.5" maxlength="36" :placeholder="t('admin.players.placeholder')" :aria-label="t('admin.players.searchLabel')" />
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!!busy">{{ busy === 'lookup' ? t('admin.players.searching') : t('common.actions.search') }}</button>
          <p v-if="queryError" role="alert" class="w-full text-xs text-redstone-300">{{ queryError }}</p>
        </form>

        <div v-if="player" class="card space-y-4 p-4" data-testid="admin-player">
          <div class="flex flex-wrap items-center gap-3">
            <span class="block size-12 overflow-hidden rounded-md"><PlayerFace :uuid="player.uuid" :name="player.name ?? '?'" /></span>
            <div class="min-w-0 flex-1">
              <p class="flex items-center gap-2 text-base font-semibold text-base-50">
                {{ player.name ?? t('common.status.unknown') }}
                <span v-if="player.admin" class="badge bg-redstone-900/50 text-redstone-300">{{ t('admin.players.admin') }}</span>
                <span v-if="player.banned" class="badge bg-redstone-600/30 text-redstone-300">{{ t('admin.players.banned') }}</span>
                <span v-if="player.online" class="badge bg-ok/10 text-ok">{{ t('common.status.online') }}</span>
              </p>
              <p class="font-mono text-[11px] text-base-400 select-all">{{ player.uuid }}</p>
            </div>
            <button class="btn btn-ghost px-2 py-1 text-[11px]" @click="copy(player.uuid, 'uuid')">{{ t('admin.players.copyUuid') }}</button>
          </div>

          <dl v-if="player.known" class="grid grid-cols-2 gap-x-6 gap-y-1 text-xs md:grid-cols-4">
            <div><dt class="text-base-400">{{ t('admin.players.since') }}</dt><dd class="text-base-50">{{ trsDate(player.createdAt) }}</dd></div>
            <div><dt class="text-base-400">{{ t('admin.players.lastLogin') }}</dt><dd class="text-base-50">{{ trsDate(player.lastLoginAt) }}</dd></div>
            <div><dt class="text-base-400">{{ t('admin.players.friends') }}</dt><dd class="text-base-50">{{ player.friends }}</dd></div>
            <div><dt class="text-base-400">{{ t('admin.players.uploadsSessions') }}</dt><dd class="text-base-50">{{ player.uploads }} / {{ player.sessions }}</dd></div>
          </dl>
          <p v-else class="text-xs text-base-400">{{ t('admin.players.neverUsed') }}</p>

          <div v-if="player.banned" class="rounded-lg border border-redstone-600/50 bg-redstone-900/30 px-3 py-2 text-xs text-redstone-300">
            {{
              player.banned.reason
                ? t('admin.players.bannedOnReason', { date: trsDate(player.banned.bannedAt), reason: player.banned.reason })
                : t('admin.players.bannedOn', { date: trsDate(player.banned.bannedAt) })
            }}
          </div>

          <div v-if="player.known">
            <p class="label">{{ t('admin.players.capes') }}</p>
            <ul v-if="player.grantedCapes.length" class="mb-2 flex flex-wrap gap-1.5">
              <li v-for="g in player.grantedCapes" :key="g.capeId" class="chip gap-1.5">
                {{ capeName(g.capeId) }}
                <span class="text-[10px] text-base-600">{{ g.source }}</span>
                <button class="text-base-400 hover:text-redstone-300" :aria-label="t('admin.players.revokeCape', { cape: capeName(g.capeId) })" :disabled="!!busy" @click="revokeGrant(g.capeId)">✕</button>
              </li>
            </ul>
            <p v-else class="mb-2 text-xs text-base-600">{{ t('admin.players.noCapes') }}</p>
            <div class="flex gap-2">
              <select v-model="grantCape" class="field w-64 py-1.5" :aria-label="t('admin.players.grantLabel')">
                <option value="" disabled>{{ t('admin.players.grantPlaceholder') }}</option>
                <option v-for="c in grantable" :key="c.id" :value="c.id">{{ c.name }} ({{ trsUnlockLabel(c) }})</option>
              </select>
              <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!grantCape || !!busy" @click="grant">{{ t('admin.players.grant') }}</button>
            </div>
          </div>

          <div class="border-t border-base-800 pt-3">
            <p class="label">{{ t('admin.players.ban') }}</p>
            <div v-if="player.banned" class="flex gap-2">
              <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="unban">{{ t('admin.players.unban') }}</button>
            </div>
            <div v-else-if="!player.admin" class="flex flex-wrap gap-2">
              <input v-model="banReason" class="field min-w-0 flex-1 py-1.5" maxlength="200" :placeholder="t('admin.players.reasonPlaceholder')" :aria-label="t('admin.players.reasonLabel')" />
              <button class="btn btn-danger px-3 py-1.5 text-xs" :disabled="!!busy" @click="banning = true">{{ t('admin.players.banButton') }}</button>
            </div>
            <p v-else class="text-xs text-base-600">{{ t('admin.players.adminsNotBannable') }}</p>
          </div>
        </div>
      </section>
    </template>

    <!-- Dialoge -------------------------------------------------------------------- -->
    <TrsCapeReviewDialog
      v-if="reviewing && reviewCapes"
      :capes="reviewCapes"
      :start-id="reviewing"
      :reload="refreshReview"
      @close="reviewing = null"
    />

    <BaseDialog v-if="rejecting" :title="t('admin.dialogs.rejectTitle')" @close="rejecting = null">
      <i18n-t keypath="admin.dialogs.rejectText" tag="p" scope="global" class="mb-3 text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ rejecting.name }}</strong></template>
      </i18n-t>
      <label class="label" for="reject-reason">{{ t('admin.dialogs.rejectReasonLabel') }}</label>
      <input id="reject-reason" v-model="rejectReason" class="field" maxlength="200" :placeholder="t('admin.dialogs.rejectPlaceholder')" @keydown.enter="confirmReject" />
      <p v-if="rejectError" role="alert" class="mt-2 text-xs text-redstone-300">{{ rejectError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="rejecting = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmReject">{{ t('admin.review.reject') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="deleting" :title="t('admin.dialogs.deleteTitle')" @close="deleting = null">
      <i18n-t keypath="admin.dialogs.deleteText" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ deleting.name }}</strong></template>
        <template #owner>{{ deleting.owner?.name ?? t('admin.dialogs.unknownOwner') }}</template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="deleting = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDeleteCape">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="revoking" :title="t('admin.dialogs.revokeTitle')" @close="revoking = null">
      <i18n-t keypath="admin.dialogs.revokeText" tag="p" scope="global" class="text-sm text-base-200">
        <template #code><span class="font-mono">…{{ revoking.hint }}</span></template>
        <template #cape>{{ capeName(revoking.capeId) }}</template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="revoking = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmRevoke">{{ t('admin.codes.revoke') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="banning && player" :title="t('admin.dialogs.banTitle')" @close="banning = false">
      <i18n-t keypath="admin.dialogs.banText" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ player.name ?? player.uuid }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="banning = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmBan">{{ t('admin.dialogs.banConfirm') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
