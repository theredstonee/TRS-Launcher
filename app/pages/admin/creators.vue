<script setup lang="ts">
import {
  cleanCodeInput,
  creatorTiers,
  normalizeCreatorCode,
  type AdminCreator,
  type AdminCreatorRequest,
  type CreatorQueueItem,
  type CreatorTier,
} from '~/utils/creator'
import { CREATOR_PETS_ADMIN_URL } from '~/utils/teamAccess'

// Creator verwalten (API §35.6): Prüfung neuer Fan-Symbole und Fan-Umhänge
// (`creators.review`), Creator-Liste mit Stufe, Haupt-Code, Entziehen/Sperren/
// Wiederherstellen und Hinzufügen sowie Anträge (Aufstieg, Team-Umhang) –
// beides `creators.manage`. Bewerbungen und Creator-Haustiere (Anträge,
// Zuordnung) laufen über die Website.
const team = useTeam()
const toasts = useToasts()

type Tab = 'queue' | 'roster' | 'requests'
const tabs = computed<Tab[]>(() => [
  ...(team.can('creators.review') ? (['queue'] as const) : []),
  ...(team.can('creators.manage') ? (['roster', 'requests'] as const) : []),
])
const tab = ref<Tab>('queue')
watch(tabs, (list) => {
  if (!list.includes(tab.value) && list[0]) tab.value = list[0]
}, { immediate: true })

// --- Prüfung -------------------------------------------------------------------------------
const queue = ref<CreatorQueueItem[] | null>(null)
async function loadQueue() {
  try {
    queue.value = await backend.creator.adminQueue()
  } catch (e) {
    queue.value = []
    toasts.error(e)
  }
}

const busy = ref<string | null>(null)
async function approve(item: CreatorQueueItem) {
  busy.value = item.id
  try {
    await backend.creator.adminDecide(item.id, true)
    queue.value = (queue.value ?? []).filter((q) => q.id !== item.id)
    toasts.ok(t('team.creators.queue.approved', { name: item.creator.name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

const reasonPresets = ['logo', 'unreadable', 'inappropriate'] as const
const rejecting = ref<CreatorQueueItem | null>(null)
const rejectPreset = ref<(typeof reasonPresets)[number] | 'other'>('logo')
const rejectText = ref('')
async function reject() {
  const item = rejecting.value
  if (!item) return
  const base = rejectPreset.value === 'other' ? '' : t(`team.creators.queue.reasons.${rejectPreset.value}`)
  const reason = [base, rejectText.value.trim()].filter(Boolean).join(' – ').slice(0, 500)
  if (!reason) {
    toasts.error(t('team.creators.queue.reasonMissing'))
    return
  }
  busy.value = item.id
  try {
    await backend.creator.adminDecide(item.id, false, reason)
    queue.value = (queue.value ?? []).filter((q) => q.id !== item.id)
    rejecting.value = null
    toasts.ok(t('team.creators.queue.rejected', { name: item.creator.name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}
function startReject(item: CreatorQueueItem) {
  rejecting.value = item
  rejectPreset.value = 'logo'
  rejectText.value = ''
}

// --- Creator-Liste --------------------------------------------------------------------------
const roster = ref<AdminCreator[] | null>(null)
const q = ref('')
const state = ref<'all' | 'active' | 'revoked' | 'banned'>('all')
async function loadRoster() {
  try {
    roster.value = await backend.creator.adminList({ q: q.value.trim() || undefined, state: state.value })
  } catch (e) {
    roster.value = []
    toasts.error(e)
  }
}
let searchTimer: ReturnType<typeof setTimeout> | null = null
watch([q, state], () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void loadRoster(), 250)
})

async function setTier(c: AdminCreator, tier: CreatorTier) {
  if (tier === c.tier) return
  busy.value = c.uuid
  try {
    await backend.creator.adminUpdate(c.uuid, { tier })
    c.tier = tier
    toasts.ok(t('team.creators.roster.tierChanged', { name: c.name, tier: t(`creator.tiers.${tier}.name`) }))
  } catch (e) {
    toasts.error(e)
    await loadRoster()
  } finally {
    busy.value = null
  }
}

const editingCode = ref<AdminCreator | null>(null)
const newMainCode = ref('')
async function saveMainCode() {
  const c = editingCode.value
  const code = normalizeCreatorCode(newMainCode.value)
  if (!c || !code) {
    toasts.error(t('creator.codes.invalid'))
    return
  }
  busy.value = c.uuid
  try {
    await backend.creator.adminUpdate(c.uuid, { mainCode: code })
    editingCode.value = null
    await loadRoster()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

const revoking = ref<{ creator: AdminCreator; ban: boolean } | null>(null)
const revokeReason = ref('')
const removeCapes = ref(false)
function startRevoke(creator: AdminCreator, ban: boolean) {
  revoking.value = { creator, ban }
  revokeReason.value = ''
  removeCapes.value = false
}
async function revoke() {
  const r = revoking.value
  if (!r) return
  const reason = revokeReason.value.trim()
  if (!reason) {
    toasts.error(t('team.creators.queue.reasonMissing'))
    return
  }
  busy.value = r.creator.uuid
  try {
    await backend.creator.adminRevoke(r.creator.uuid, { reason, ban: r.ban, removeFanCapes: removeCapes.value })
    revoking.value = null
    toasts.ok(r.ban ? t('team.creators.roster.bannedToast', { name: r.creator.name }) : t('team.creators.roster.revokedToast', { name: r.creator.name }))
    await loadRoster()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}
async function restore(c: AdminCreator) {
  busy.value = c.uuid
  try {
    await backend.creator.adminRestore(c.uuid)
    toasts.ok(t('team.creators.roster.restoredToast', { name: c.name }))
    await loadRoster()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

const adding = reactive({ player: '', tier: 'starter' as CreatorTier, mainCode: '' })
async function add() {
  const player = adding.player.trim()
  if (!player) return
  const mainCode = adding.mainCode ? normalizeCreatorCode(adding.mainCode) : null
  if (adding.mainCode && !mainCode) {
    toasts.error(t('creator.codes.invalid'))
    return
  }
  busy.value = 'add'
  try {
    await backend.creator.adminAdd({ player, tier: adding.tier, ...(mainCode ? { mainCode } : {}) })
    toasts.ok(t('team.creators.roster.added', { name: player }))
    adding.player = ''
    adding.mainCode = ''
    await loadRoster()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

// --- Anträge ----------------------------------------------------------------------------------
const requests = ref<AdminCreatorRequest[] | null>(null)
async function loadRequests() {
  try {
    requests.value = await backend.creator.adminRequests()
  } catch (e) {
    requests.value = []
    toasts.error(e)
  }
}
const answering = ref<{ request: AdminCreatorRequest; accept: boolean } | null>(null)
const answerReason = ref('')
async function answer() {
  const a = answering.value
  if (!a) return
  busy.value = a.request.id
  try {
    await backend.creator.adminAnswer(a.request.id, a.accept, answerReason.value.trim() || null)
    answering.value = null
    answerReason.value = ''
    await loadRequests()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

/** Dashboard des Creators im Team-Modus öffnen (alle Rechte, jede Änderung wird protokolliert). */
function openDashboard(c: AdminCreator) {
  void navigateTo({ path: '/creator', query: { view: c.uuid, team: '1', name: c.name } })
}

function openUrl(url: string) {
  void backend.openExternalUrl(url).catch((e) => toasts.error(e))
}

watch(
  tab,
  (v) => {
    if (v === 'queue' && !queue.value) void loadQueue()
    if (v === 'roster' && !roster.value) void loadRoster()
    if (v === 'requests' && !requests.value) void loadRequests()
  },
  { immediate: true },
)

const stateClass = { active: 'bg-ok/15 text-ok', revoked: 'bg-base-800 text-base-400', banned: 'bg-redstone-900 text-redstone-300' } as const
</script>

<template>
  <section class="space-y-4" :aria-label="t('team.nav.creators')" data-testid="admin-creators">
    <p class="text-xs text-base-400">{{ t('team.creators.intro') }}</p>
    <div v-if="team.can('creators.manage')" class="card flex flex-wrap items-center gap-3 px-4 py-3" data-testid="admin-creator-pets-link">
      <SocialIcon name="star" class="size-5 shrink-0 text-lamp-300" />
      <div class="min-w-0 flex-1">
        <p class="text-sm font-medium text-base-50">{{ t('team.creators.pets.title') }}</p>
        <p class="text-xs text-base-400">{{ t('team.creators.pets.text') }}</p>
      </div>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="openUrl(CREATOR_PETS_ADMIN_URL)">
        {{ t('team.creators.pets.open') }} <SocialIcon name="external" class="size-3.5" />
      </button>
    </div>
    <div class="flex gap-1" role="tablist">
      <button v-for="k in tabs" :key="k" class="tab" :class="{ 'tab-on': tab === k }" role="tab" :aria-selected="tab === k" @click="tab = k">
        {{ t(`team.creators.tabs.${k}`) }}
        <span v-if="k === 'queue' && queue?.length" class="ml-1 badge bg-lamp-900 text-lamp-300">{{ queue.length }}</span>
      </button>
    </div>

    <!-- Prüfung -->
    <template v-if="tab === 'queue'">
      <div v-if="!queue" class="skeleton h-32" />
      <p v-else-if="!queue.length" class="card px-4 py-5 text-sm text-base-400">{{ t('team.creators.queue.empty') }}</p>
      <ul v-else class="card divide-y divide-base-800">
        <li v-for="item in queue" :key="item.id" class="flex flex-wrap items-center gap-4 px-4 py-3" :data-testid="`creator-queue-${item.id}`">
          <div v-if="item.symbol" class="flex items-end gap-3">
            <CreatorSymbol :image="item.symbol.image" :frames="item.symbol.frames" :frame-ms="item.symbol.frameMs" :px="64" />
            <CreatorSymbol :image="item.symbol.image" :frames="item.symbol.frames" :frame-ms="item.symbol.frameMs" :px="16" />
          </div>
          <div v-else-if="item.cape" class="rounded-lg bg-base-850 p-1.5">
            <CapeThumb :texture="item.cape.texture" :scale="item.cape.scale" :frames="item.cape.frames" :frame-time-ms="item.cape.frames > 1 ? 100 : null" :width="44" />
          </div>
          <div class="min-w-0 flex-1">
            <p class="text-sm text-base-50">
              <b>{{ item.kind === 'symbol' ? t('team.creators.queue.symbol') : t('team.creators.queue.cape') }}</b>
              · {{ item.creator.name }} <CreatorTierBadge :tier="item.creator.tier" />
            </p>
            <p class="text-xs text-base-400">
              <template v-if="item.symbol">{{ t('team.creators.queue.symbolInfo', { size: item.symbol.size, frames: item.symbol.frames }) }}</template>
              <template v-else-if="item.cape">{{ item.cape.name }} · {{ 64 * item.cape.scale }}×{{ 32 * item.cape.scale }} · {{ t('team.creators.queue.frames', { n: item.cape.frames }) }}</template>
              <template v-if="item.createdAt"> · {{ formatRelative(item.createdAt, true) }}</template>
            </p>
          </div>
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="busy === item.id" @click="approve(item)">{{ t('team.creators.queue.approve') }}</button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy === item.id" @click="startReject(item)">{{ t('team.creators.queue.reject') }}</button>
        </li>
      </ul>
    </template>

    <!-- Creator -->
    <template v-else-if="tab === 'roster'">
      <form class="card grid grid-cols-1 gap-3 p-4 md:grid-cols-[2fr_1fr_1.4fr_auto]" @submit.prevent="add">
        <label class="block">
          <span class="label">{{ t('team.creators.roster.player') }}</span>
          <input v-model="adding.player" class="field" maxlength="36" :placeholder="t('team.creators.roster.playerPlaceholder')" />
        </label>
        <label class="block">
          <span class="label">{{ t('team.creators.roster.tier') }}</span>
          <select v-model="adding.tier" class="field">
            <option v-for="tier in creatorTiers" :key="tier" :value="tier">{{ t(`creator.tiers.${tier}.name`) }}</option>
          </select>
        </label>
        <label class="block">
          <span class="label">{{ t('team.creators.roster.mainCodeOptional') }}</span>
          <input :value="adding.mainCode" class="field font-mono uppercase" maxlength="20" placeholder="TRS-…" @input="adding.mainCode = cleanCodeInput(($event.target as HTMLInputElement).value)" />
        </label>
        <div class="flex items-end">
          <button class="btn btn-primary w-full" :disabled="busy === 'add' || !adding.player.trim()">{{ t('team.creators.roster.add') }}</button>
        </div>
      </form>

      <div class="flex flex-wrap gap-2">
        <input v-model="q" class="field max-w-xs" :placeholder="t('team.creators.roster.search')" :aria-label="t('team.creators.roster.search')" />
        <select v-model="state" class="field w-auto" :aria-label="t('team.creators.roster.state')">
          <option v-for="s in ['all', 'active', 'revoked', 'banned'] as const" :key="s" :value="s">{{ t(`team.creators.roster.states.${s}`) }}</option>
        </select>
      </div>

      <div v-if="!roster" class="skeleton h-40" />
      <p v-else-if="!roster.length" class="card px-4 py-5 text-sm text-base-400">{{ t('team.creators.roster.empty') }}</p>
      <div v-else class="card overflow-x-auto">
        <table class="w-full min-w-[46rem] text-sm">
          <thead>
            <tr class="text-left text-[11px] tracking-wide text-base-400 uppercase">
              <th class="px-4 py-2 font-medium">{{ t('team.creators.roster.creator') }}</th>
              <th class="py-2 font-medium">{{ t('team.creators.roster.tier') }}</th>
              <th class="py-2 font-medium">{{ t('team.creators.roster.fans') }}</th>
              <th class="py-2 font-medium">{{ t('team.creators.roster.mainCode') }}</th>
              <th class="py-2 font-medium">{{ t('team.creators.roster.state') }}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in roster" :key="c.uuid" class="border-t border-base-800 align-top">
              <td class="px-4 py-2">
                <div class="flex items-center gap-2">
                  <span class="block size-6 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="c.uuid" :name="c.name" /></span>
                  <NuxtLink :to="`/admin/players/${c.uuid}`" class="font-medium text-base-50 hover:underline">{{ c.name }}</NuxtLink>
                </div>
                <p v-if="c.reason" class="mt-0.5 text-xs text-base-400">{{ c.reason }}</p>
              </td>
              <td class="py-2">
                <select class="field w-auto px-2 py-1 text-xs" :value="c.tier" :disabled="busy === c.uuid" @change="setTier(c, ($event.target as HTMLSelectElement).value as CreatorTier)">
                  <option v-for="tier in creatorTiers" :key="tier" :value="tier">{{ t(`creator.tiers.${tier}.name`) }}</option>
                </select>
              </td>
              <td class="py-2 tabular-nums">{{ formatNumber(c.fans) }}</td>
              <td class="py-2">
                <button class="font-mono text-xs font-semibold text-redstone-300 hover:underline" :title="t('team.creators.roster.editCode')" @click="(editingCode = c), (newMainCode = c.mainCode ?? '')">
                  {{ c.mainCode ?? '–' }}
                </button>
              </td>
              <td class="py-2"><span class="badge" :class="stateClass[c.state]">{{ t(`team.creators.roster.states.${c.state}`) }}</span></td>
              <td class="py-2 pr-4 text-right whitespace-nowrap">
                <template v-if="c.state === 'active'">
                  <button class="btn btn-ghost px-3 py-1 text-xs" :data-testid="`creator-open-dashboard-${c.uuid}`" :title="t('creator.teamMode.openDashboardHint')" @click="openDashboard(c)">
                    {{ t('creator.teamMode.openDashboard') }}
                  </button>
                  <NuxtLink :to="`/admin/creator-stats/${c.uuid}`" class="btn btn-ghost px-3 py-1 text-xs">{{ t('team.creatorStats.detail.open') }}</NuxtLink>
                  <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="busy === c.uuid" @click="startRevoke(c, false)">{{ t('team.creators.roster.revoke') }}</button>
                  <button class="btn btn-danger ml-1 px-3 py-1 text-xs" :disabled="busy === c.uuid" @click="startRevoke(c, true)">{{ t('team.creators.roster.ban') }}</button>
                </template>
                <button v-else class="btn btn-ghost px-3 py-1 text-xs" :disabled="busy === c.uuid" @click="restore(c)">{{ t('team.creators.roster.restore') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-400">{{ t('team.creators.roster.note') }}</p>
    </template>

    <!-- Anträge -->
    <template v-else-if="tab === 'requests'">
      <div v-if="!requests" class="skeleton h-32" />
      <p v-else-if="!requests.length" class="card px-4 py-5 text-sm text-base-400">{{ t('team.creators.requests.empty') }}</p>
      <ul v-else class="card divide-y divide-base-800">
        <li v-for="r in requests" :key="r.id" class="flex flex-wrap items-start gap-3 px-4 py-3">
          <div class="min-w-0 flex-1">
            <p class="text-sm text-base-50">
              <b>{{ t(`creator.tier.kind.${r.kind}`) }}</b>
              <template v-if="r.creator"> · {{ r.creator.name }} <CreatorTierBadge :tier="r.creator.tier" /></template>
              <span class="ml-1 badge" :class="r.status === 'open' ? 'bg-lamp-900 text-lamp-300' : r.status === 'accepted' ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-400'">{{ t(`creator.tier.status.${r.status}`) }}</span>
            </p>
            <p v-if="r.channelUrl || r.followers !== null" class="mt-0.5 text-xs text-base-400">
              <button v-if="r.channelUrl" class="hover:text-base-50 hover:underline" @click="openUrl(r.channelUrl)">{{ r.channelUrl }}</button>
              <template v-if="r.followers !== null"> · {{ t('team.creators.requests.followers', { n: formatNumber(r.followers) }) }}</template>
            </p>
            <p v-if="r.note || r.text" class="mt-1 text-xs whitespace-pre-line text-base-200">{{ r.note ?? r.text }}</p>
            <p v-if="r.reason" class="mt-1 text-xs text-base-400">{{ r.reason }}</p>
          </div>
          <template v-if="r.status === 'open'">
            <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="busy === r.id" @click="answering = { request: r, accept: true }">{{ t('team.creators.requests.accept') }}</button>
            <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy === r.id" @click="answering = { request: r, accept: false }">{{ t('team.creators.requests.decline') }}</button>
          </template>
        </li>
      </ul>
    </template>

    <BaseDialog v-if="rejecting" :title="t('team.creators.queue.rejectTitle', { name: rejecting.creator.name })" @close="rejecting = null">
      <label class="label" for="creator-reject-preset">{{ t('team.creators.queue.reason') }}</label>
      <select id="creator-reject-preset" v-model="rejectPreset" class="field">
        <option v-for="r in reasonPresets" :key="r" :value="r">{{ t(`team.creators.queue.reasons.${r}`) }}</option>
        <option value="other">{{ t('team.creators.queue.reasons.other') }}</option>
      </select>
      <label class="label mt-3" for="creator-reject-text">{{ t('team.creators.queue.message') }}</label>
      <textarea id="creator-reject-text" v-model="rejectText" class="field" rows="3" maxlength="400" />
      <template #actions>
        <button class="btn btn-ghost" @click="rejecting = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy === rejecting.id" @click="reject">{{ t('team.creators.queue.reject') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog
      v-if="revoking"
      :title="revoking.ban ? t('team.creators.roster.banTitle', { name: revoking.creator.name }) : t('team.creators.roster.revokeTitle', { name: revoking.creator.name })"
      @close="revoking = null"
    >
      <label class="label" for="creator-revoke-reason">{{ t('team.creators.roster.reason') }}</label>
      <input id="creator-revoke-reason" v-model="revokeReason" class="field" maxlength="500" :placeholder="t('team.creators.roster.reasonPlaceholder')" />
      <label class="mt-3 flex items-center gap-2 text-sm text-base-200">
        <input v-model="removeCapes" type="checkbox" class="accent-redstone-500" />{{ t('team.creators.roster.removeCapes') }}
      </label>
      <p class="mt-2 text-xs text-base-400">{{ revoking.ban ? t('team.creators.roster.banText') : t('team.creators.roster.revokeText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="revoking = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy === revoking.creator.uuid" @click="revoke">
          {{ revoking.ban ? t('team.creators.roster.ban') : t('team.creators.roster.revoke') }}
        </button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="editingCode" :title="t('team.creators.roster.editCodeTitle', { name: editingCode.name })" @close="editingCode = null">
      <input :value="newMainCode" class="field font-mono font-bold tracking-widest uppercase" maxlength="20" @input="newMainCode = cleanCodeInput(($event.target as HTMLInputElement).value)" />
      <p class="mt-2 text-xs text-base-400">{{ t('team.creators.roster.editCodeHint') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="editingCode = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy === editingCode.uuid" @click="saveMainCode">{{ t('team.creators.roster.save') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog
      v-if="answering"
      :title="answering.accept ? t('team.creators.requests.acceptTitle') : t('team.creators.requests.declineTitle')"
      @close="answering = null"
    >
      <p v-if="answering.accept && answering.request.kind === 'upgrade'" class="mb-3 text-sm text-base-200">{{ t('team.creators.requests.upgradeHint') }}</p>
      <label class="label" for="creator-answer-reason">{{ t('team.creators.requests.reason') }}</label>
      <input id="creator-answer-reason" v-model="answerReason" class="field" maxlength="500" />
      <template #actions>
        <button class="btn btn-ghost" @click="answering = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn" :class="answering.accept ? 'btn-primary' : 'btn-danger'" :disabled="busy === answering.request.id" @click="answer">
          {{ answering.accept ? t('team.creators.requests.accept') : t('team.creators.requests.decline') }}
        </button>
      </template>
    </BaseDialog>
  </section>
</template>
