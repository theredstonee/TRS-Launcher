<script setup lang="ts">
import { reportReasonIds, type ReportReason } from '~/utils/chat'
import type { AdminReportSummary } from '~/utils/moderation'
import type { TrsCape } from '~/utils/trs'
import type { AdminRoom, AdminSanction, PlayerFile } from '~/utils/team'

// Spieler-Akte: Kopf mit Rolle/Online/Anmeldungen, Kennzahlen, und Reiter für
// Strafverlauf (aufheben, verkürzen/verlängern), Meldungen (gegen/von),
// Uploads + Vergaben, Welten, frühere Namen und interne Notizen.
const route = useRoute()
const toasts = useToasts()
const team = useTeam()

const uuid = computed(() => String(route.params.uuid ?? '').replace(/-/g, '').toLowerCase())
const file = ref<PlayerFile | null>(null)
const unknown = ref(false)
const loadError = ref<string | null>(null)
type Tab = 'sanctions' | 'reports' | 'uploads' | 'worlds' | 'names' | 'notes'
const tab = ref<Tab>('sanctions')
const onlyActive = ref(false)
const newSanction = ref(false)
const changing = ref<{ sanction: AdminSanction; mode: 'lift' | 'change' } | null>(null)
const openReport = ref<string | null>(null)
const closingRoom = ref<AdminRoom | null>(null)
const busy = ref(false)

async function load() {
  loadError.value = null
  try {
    file.value = (await backend.team.player(uuid.value)).file
    unknown.value = false
  } catch (e) {
    if (e instanceof BackendError && e.apiCode === 'user_not_found') {
      unknown.value = true
      file.value = null
    } else loadError.value = errorMessage(e)
  }
}
watch(uuid, () => {
  file.value = null
  void load()
})
onMounted(load)

const p = computed(() => file.value?.player ?? null)
const name = computed(() => p.value?.name ?? uuid.value)
const sanctions = computed(() => (file.value?.sanctions ?? []).filter((s) => !onlyActive.value || s.status === 'active'))
const activeCount = computed(() => (file.value?.sanctions ?? []).filter((s) => s.status === 'active').length)
const canSanction = computed(() => (file.value ? file.value.can.sanction : unknown.value))

const tabs = computed<{ id: Tab; count?: number }[]>(() => {
  const f = file.value
  return [
    { id: 'sanctions', count: f?.sanctions.length },
    { id: 'reports', count: f ? f.reports.against.counts.total : undefined },
    { id: 'uploads', count: f ? f.capes.length + f.cosmetics.length : undefined },
    { id: 'worlds', count: f?.worlds.length },
    { id: 'names', count: f?.names.length },
    { id: 'notes', count: f?.notes.length },
  ]
})

async function copyUuid() {
  try {
    await navigator.clipboard.writeText(uuid.value)
    toasts.ok(t('admin.toasts.copied.uuid'))
  } catch {
    toasts.error(t('admin.toasts.copyFailed'))
  }
}

function created() {
  newSanction.value = false
  toasts.ok(t('team.sanction.created'))
  void load()
  void team.refreshCounts()
}
function changed() {
  changing.value = null
  toasts.ok(t('team.change.done'))
  void load()
}

// --- Notizen ----------------------------------------------------------------------------
const note = ref('')
async function addNote() {
  const text = note.value.trim()
  if (!text || !file.value) return
  busy.value = true
  try {
    file.value.notes = (await backend.team.addNote(uuid.value, text)).notes
    note.value = ''
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
async function deleteNote(id: number) {
  if (!file.value) return
  try {
    file.value.notes = (await backend.team.deleteNote(uuid.value, id)).notes
  } catch (e) {
    toasts.error(e)
  }
}

// --- Umhänge vergeben (nur Admins) ------------------------------------------------------
const catalog = ref<TrsCape[]>([])
const grantCape = ref('')
const grantable = computed(() => catalog.value.filter((c) => c.kind === 'builtin' && c.unlock !== 'free'))
watch(tab, async (v) => {
  if (v === 'uploads' && team.isAdmin.value && !catalog.value.length) catalog.value = await backend.trs.capes().catch(() => [])
})
async function grant() {
  if (!grantCape.value) return
  busy.value = true
  try {
    const already = await backend.trs.adminGrant(uuid.value, grantCape.value)
    toasts.ok(already ? t('admin.toasts.alreadyHad') : t('admin.toasts.granted', { cape: capeName(grantCape.value), player: name.value }))
    grantCape.value = ''
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
async function revokeGrant(capeId: string) {
  try {
    await backend.trs.adminRevokeGrant(uuid.value, capeId)
    toasts.ok(t('admin.toasts.grantRevoked'))
    await load()
  } catch (e) {
    toasts.error(e)
  }
}
const capeName = (id: string) => catalog.value.find((c) => c.id === id)?.name ?? file.value?.capes.find((c) => c.id === id)?.name ?? id

// --- Welten -------------------------------------------------------------------------------
async function closeRoom(reason: string) {
  const room = closingRoom.value
  if (!room) return
  busy.value = true
  try {
    await backend.team.closeRoom(room.id, reason || null)
    toasts.ok(t('team.worlds.closed', { name: room.name }))
    closingRoom.value = null
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

const reasonLabel = (r: string) => ((reportReasonIds as readonly string[]).includes(r) ? t(`social.report.reasons.${r as ReportReason}`) : r)
const reportLine = (r: AdminReportSummary) => `${reasonLabel(r.reason)} · ${t(`social.report.titles.${r.kind}`)}`
</script>

<template>
  <section :aria-label="t('team.file.title')" data-testid="admin-player-file">
    <NuxtLink to="/admin/players" class="mb-3 inline-flex items-center gap-1 text-xs text-base-400 hover:text-base-50">← {{ t('team.file.back') }}</NuxtLink>

    <p v-if="loadError" role="alert" class="card px-4 py-6 text-sm text-redstone-300">{{ loadError }}</p>
    <div v-else-if="!file && !unknown" class="space-y-3"><div class="skeleton h-32" /><div class="skeleton h-64" /></div>

    <!-- Nie angemeldet: vorsorglich bestrafen geht trotzdem -->
    <div v-else-if="unknown" class="card flex flex-wrap items-center gap-4 p-5">
      <span class="block size-14 overflow-hidden rounded-lg"><PlayerFace :uuid="uuid" name="?" /></span>
      <div class="min-w-0 flex-1">
        <p class="font-semibold text-base-50">{{ t('team.file.unknown') }}</p>
        <p class="font-mono text-xs text-base-400">{{ uuid }}</p>
        <p class="mt-1 text-xs text-base-400">{{ t('team.file.unknownHint') }}</p>
      </div>
      <button class="btn btn-primary" @click="newSanction = true"><SocialIcon name="gavel" class="size-4" />{{ t('team.file.newSanction') }}</button>
    </div>

    <template v-else-if="file && p">
      <!-- Kopf -->
      <div class="card overflow-hidden">
        <div class="flex flex-wrap items-center gap-4 p-5" :class="p.banned ? 'bg-redstone-900/40' : ''">
          <span class="block size-16 shrink-0 overflow-hidden rounded-lg ring-2" :class="p.online ? 'ring-ok/60' : 'ring-base-700'">
            <PlayerFace :uuid="p.uuid" :name="p.name ?? '?'" />
          </span>
          <div class="min-w-0 flex-1">
            <h2 class="flex flex-wrap items-center gap-2 text-2xl font-semibold text-base-50">
              <span class="display">{{ name }}</span>
              <span v-if="p.role" class="badge bg-redstone-900/60 text-redstone-300">{{ t(`team.roles.${p.role}`) }}</span>
              <span v-if="p.banned" class="badge bg-redstone-600/40 text-redstone-300">{{ t('team.file.banned') }}</span>
              <span v-if="p.online" class="badge bg-ok/15 text-ok">{{ t('common.status.online') }}</span>
            </h2>
            <p class="mt-0.5 flex items-center gap-2 font-mono text-[11px] text-base-400">
              <span class="select-all">{{ p.uuid }}</span>
              <button class="text-base-400 hover:text-base-50" :title="t('admin.players.copyUuid')" :aria-label="t('admin.players.copyUuid')" @click="copyUuid"><SocialIcon name="copy" class="size-3.5" /></button>
            </p>
            <dl class="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs">
              <div><dt class="inline text-base-400">{{ t('team.file.firstLogin') }}: </dt><dd class="inline text-base-100">{{ formatShortDate(p.firstLoginAt) }}</dd></div>
              <div><dt class="inline text-base-400">{{ t('team.file.lastLogin') }}: </dt><dd class="inline text-base-100">{{ p.lastLoginAt ? formatRelative(p.lastLoginAt) : '–' }}</dd></div>
              <div><dt class="inline text-base-400">{{ t('team.file.friends') }}: </dt><dd class="inline text-base-100">{{ p.friends }}</dd></div>
              <div><dt class="inline text-base-400">{{ t('team.file.sessions') }}: </dt><dd class="inline text-base-100">{{ p.sessions }}</dd></div>
            </dl>
          </div>
          <div class="flex flex-col items-end gap-2">
            <button class="btn btn-primary" :disabled="!canSanction" data-testid="file-new-sanction" @click="newSanction = true">
              <SocialIcon name="gavel" class="size-4" />{{ t('team.file.newSanction') }}
            </button>
            <p v-if="!canSanction && file.can.reason" class="max-w-56 text-right text-[11px] text-base-400">{{ t(`team.file.cannot.${file.can.reason}`) }}</p>
          </div>
        </div>
        <!-- Kennzahlen -->
        <div class="grid grid-cols-2 border-t border-base-800 text-center sm:grid-cols-4">
          <div class="border-r border-base-800 px-3 py-2.5">
            <p class="text-lg font-semibold tabular-nums" :class="activeCount ? 'text-redstone-300' : 'text-base-50'">{{ activeCount }}</p>
            <p class="text-[11px] text-base-400">{{ t('team.file.activeSanctions') }}</p>
          </div>
          <div class="border-r border-base-800 px-3 py-2.5">
            <p class="text-lg font-semibold text-base-50 tabular-nums">{{ file.warnings.active }} / {{ file.warnings.total }}</p>
            <p class="text-[11px] text-base-400">{{ t('team.file.warnings') }}</p>
          </div>
          <div class="border-r border-base-800 px-3 py-2.5">
            <p class="text-lg font-semibold tabular-nums" :class="file.reports.against.counts.open ? 'text-lamp-300' : 'text-base-50'">
              {{ file.reports.against.counts.open }} / {{ file.reports.against.counts.total }}
            </p>
            <p class="text-[11px] text-base-400">{{ t('team.file.reportsAgainst') }}</p>
          </div>
          <div class="px-3 py-2.5">
            <p class="text-lg font-semibold tabular-nums" :class="file.reports.reporterScore.low ? 'text-lamp-300' : 'text-base-50'">
              {{ file.reports.reporterScore.score === null ? '–' : `${Math.round(file.reports.reporterScore.score)} %` }}
            </p>
            <p class="text-[11px] text-base-400">{{ t('team.file.reporterScore', { n: file.reports.filed.counts.total }) }}</p>
          </div>
        </div>
      </div>

      <!-- Reiter -->
      <div class="mt-5 mb-4 flex flex-wrap gap-1" role="tablist">
        <button v-for="x in tabs" :key="x.id" class="tab" :class="{ 'tab-on': tab === x.id }" role="tab" :aria-selected="tab === x.id" :data-testid="`file-tab-${x.id}`" @click="tab = x.id">
          {{ t(`team.file.tabs.${x.id}`) }}
          <span v-if="x.count" class="ml-1 text-xs text-base-400 tabular-nums">{{ x.count }}</span>
        </button>
      </div>

      <!-- Strafverlauf -->
      <div v-if="tab === 'sanctions'" class="space-y-3">
        <label class="flex items-center gap-2 text-xs text-base-400">
          <input v-model="onlyActive" type="checkbox" class="accent-redstone-500" />{{ t('team.file.onlyActive') }}
        </label>
        <p v-if="!sanctions.length" class="card px-4 py-6 text-center text-sm text-base-400">{{ t('team.file.noSanctions') }}</p>
        <AdminSanctionCard
          v-for="s in sanctions"
          :key="s.id"
          :sanction="s"
          @lift="changing = { sanction: s, mode: 'lift' }"
          @change="changing = { sanction: s, mode: 'change' }"
        />
      </div>

      <!-- Meldungen -->
      <div v-else-if="tab === 'reports'" class="grid gap-4 lg:grid-cols-2">
        <div v-for="side in (['against', 'filed'] as const)" :key="side" class="card p-4">
          <h3 class="section-title">{{ t(`team.file.reports.${side}`) }}</h3>
          <p class="mt-1 text-xs text-base-400">{{ t('team.file.reports.counts', file.reports[side].counts) }}</p>
          <ul v-if="file.reports[side].recent.length" class="mt-3 space-y-1.5">
            <li v-for="r in file.reports[side].recent" :key="r.id">
              <button class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs hover:bg-base-850" @click="openReport = r.id">
                <span class="badge" :class="r.status === 'resolved' ? 'bg-base-800 text-base-400' : 'bg-lamp-900 text-lamp-300'">
                  {{ r.status === 'resolved' && r.outcome ? t(`admin.mod.outcome.${r.outcome}`) : t(`admin.mod.status.${r.status}`) }}
                </span>
                <span class="min-w-0 flex-1 truncate text-base-100">{{ reportLine(r) }}</span>
                <span class="text-base-400">{{ formatShortDate(r.createdAt) }}</span>
              </button>
            </li>
          </ul>
          <p v-else class="mt-3 text-xs text-base-600">{{ t('team.common.empty') }}</p>
        </div>
      </div>

      <!-- Uploads -->
      <div v-else-if="tab === 'uploads'" class="space-y-4">
        <div class="card p-4">
          <h3 class="section-title">{{ t('team.file.capes') }}</h3>
          <ul v-if="file.capes.length" class="mt-3 flex flex-wrap gap-2">
            <li v-for="c in file.capes" :key="c.id" class="chip gap-2">
              <span class="text-base-50">{{ c.name || c.id }}</span>
              <span class="text-[10px] text-base-400">{{ t(`team.file.source.${c.source}`) }} · {{ c.status }}</span>
              <span v-if="c.reports" class="text-[10px] text-lamp-300">⚑ {{ c.reports }}</span>
              <button
                v-if="team.isAdmin.value && c.source !== 'upload'"
                class="text-base-400 hover:text-redstone-300"
                :aria-label="t('admin.players.revokeCape', { cape: c.name || c.id })"
                @click="revokeGrant(c.id)"
              >✕</button>
            </li>
          </ul>
          <p v-else class="mt-2 text-xs text-base-600">{{ t('admin.players.noCapes') }}</p>
          <div v-if="team.isAdmin.value && p.known" class="mt-3 flex gap-2">
            <select v-model="grantCape" class="field w-64 py-1.5" :aria-label="t('admin.players.grantLabel')">
              <option value="" disabled>{{ t('admin.players.grantPlaceholder') }}</option>
              <option v-for="c in grantable" :key="c.id" :value="c.id">{{ c.name }} ({{ trsUnlockLabel(c) }})</option>
            </select>
            <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!grantCape || busy" @click="grant">{{ t('admin.players.grant') }}</button>
          </div>
        </div>
        <div class="card p-4">
          <h3 class="section-title">{{ t('team.file.cosmetics') }}</h3>
          <ul v-if="file.cosmetics.length" class="mt-3 flex flex-wrap gap-2">
            <li v-for="c in file.cosmetics" :key="c.id" class="chip gap-2">
              <span class="text-base-50">{{ c.name || c.id }}</span>
              <span class="text-[10px] text-base-400">{{ c.slot }} · {{ t(`team.file.source.${c.source}`) }} · {{ c.status }}</span>
              <span v-if="c.reports" class="text-[10px] text-lamp-300">⚑ {{ c.reports }}</span>
            </li>
          </ul>
          <p v-else class="mt-2 text-xs text-base-600">{{ t('team.common.empty') }}</p>
        </div>
      </div>

      <!-- Welten -->
      <div v-else-if="tab === 'worlds'" class="space-y-2">
        <p v-if="!file.worlds.length" class="card px-4 py-6 text-center text-sm text-base-400">{{ t('team.worlds.none') }}</p>
        <div v-for="w in file.worlds" :key="w.id" class="card flex flex-wrap items-center gap-3 px-4 py-3 text-sm">
          <SocialIcon name="world" class="size-5 text-base-400" />
          <div class="min-w-0 flex-1">
            <p class="font-semibold text-base-50">{{ w.name }} <span class="font-mono text-xs text-base-400">{{ w.code }}</span></p>
            <p class="text-xs text-base-400">{{ t('team.worlds.line', { host: w.host.name, version: w.mcVersion, loader: w.loader, players: w.players, max: w.maxPlayers }) }}</p>
          </div>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="closingRoom = w">{{ t('team.worlds.close') }}</button>
        </div>
      </div>

      <!-- Namen -->
      <div v-else-if="tab === 'names'" class="card overflow-hidden">
        <table class="w-full text-left text-sm">
          <thead class="text-xs text-base-400">
            <tr class="border-b border-base-800">
              <th class="px-4 py-2 font-medium">{{ t('team.file.name') }}</th>
              <th class="px-3 py-2 font-medium">{{ t('team.file.firstSeen') }}</th>
              <th class="px-3 py-2 font-medium">{{ t('team.file.lastSeen') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="n in file.names" :key="n.name + n.firstSeen" class="border-b border-base-800/60 last:border-0">
              <td class="px-4 py-2 font-semibold text-base-50">{{ n.name }}</td>
              <td class="px-3 py-2 text-xs text-base-200">{{ formatDate(n.firstSeen) }}</td>
              <td class="px-3 py-2 text-xs text-base-200">{{ formatDate(n.lastSeen) }}</td>
            </tr>
            <tr v-if="!file.names.length"><td colspan="3" class="px-4 py-4 text-center text-xs text-base-400">{{ t('team.common.empty') }}</td></tr>
          </tbody>
        </table>
      </div>

      <!-- Notizen -->
      <div v-else class="space-y-3">
        <form class="card p-4" @submit.prevent="addNote">
          <label class="label" for="file-note">{{ t('team.file.noteLabel') }}</label>
          <textarea id="file-note" v-model="note" class="field min-h-20 resize-y" maxlength="2000" :placeholder="t('team.file.noteHint')" data-testid="file-note" />
          <div class="mt-2 flex items-center justify-between">
            <span class="text-[11px] text-base-400">{{ t('team.file.noteInternal') }}</span>
            <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="busy || !note.trim()">{{ t('admin.mod.addNote') }}</button>
          </div>
        </form>
        <p v-if="!file.notes.length" class="text-center text-xs text-base-400">{{ t('team.file.noNotes') }}</p>
        <div v-for="n in file.notes" :key="n.id" class="card px-4 py-3">
          <p class="flex items-center gap-2 text-xs text-base-400">
            <span class="font-medium text-base-100">{{ n.actor.name ?? n.actor.uuid }}</span>
            <span>{{ formatDate(n.at) }}</span>
            <button v-if="n.deletable" class="ml-auto text-base-400 hover:text-redstone-300" :aria-label="t('common.actions.delete')" @click="deleteNote(n.id)">
              <SocialIcon name="trash" class="size-3.5" />
            </button>
          </p>
          <p class="mt-1 text-sm whitespace-pre-wrap text-base-50">{{ n.text }}</p>
        </div>
      </div>
    </template>

    <AdminSanctionDialog v-if="newSanction" :player="{ uuid, name: p?.name ?? null }" @close="newSanction = false" @created="created" />
    <AdminSanctionChangeDialog v-if="changing" :sanction="changing.sanction" :mode="changing.mode" @close="changing = null" @changed="changed" />
    <AdminReportDialog v-if="openReport" :key="openReport" :report-id="openReport" @close="openReport = null" @changed="load" @open="(id) => (openReport = id)" />
    <AdminConfirm
      v-if="closingRoom"
      :title="t('team.worlds.closeTitle', { name: closingRoom.name })"
      :text="t('team.worlds.closeText')"
      :confirm-label="t('team.worlds.close')"
      danger
      :busy="busy"
      :reason="{ label: t('team.worlds.reason'), max: 200 }"
      @confirm="closeRoom"
      @close="closingRoom = null"
    />
  </section>
</template>
