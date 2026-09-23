<script setup lang="ts">
import type { TrsAdminCape, TrsAdminStats, TrsAdminUser, TrsCape, TrsCode, TrsReviewList } from '~/utils/trs'

// Verwaltung der TRS-Dienste – nur für Admins (der Server prüft das bei jeder
// Anfrage selbst; die Seite blendet sich für alle anderen nur aus).
const trs = useTrsStore()
const toasts = useToasts()

type Tab = 'overview' | 'capes' | 'codes' | 'players'
const tab = ref<Tab>('overview')
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
const lists: [TrsReviewList, string][] = [
  ['pending', 'Wartend'],
  ['reported', 'Gemeldet'],
  ['approved', 'Freigegeben'],
  ['rejected', 'Abgelehnt'],
]
const reasonNames: Record<string, string> = {
  inappropriate: 'unangemessen',
  copyright: 'Urheberrecht',
  impersonation: 'Identität',
  other: 'sonstiges',
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
    toasts.ok(`„${cape.name}“ freigegeben`)
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
    toasts.ok(`„${cape.name}“ abgelehnt`)
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
    toasts.ok('Umhang gelöscht')
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
    codeError.value = codeForm.capeId ? firstIssue(parsed.error) : 'Bitte einen Umhang wählen.'
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
    toasts.ok(`${created.value.length} ${created.value.length === 1 ? 'Code' : 'Codes'} erstellt`)
    await Promise.all([loadCodes(), loadStats()])
  })
}

async function copy(text: string, what = 'Code') {
  try {
    await navigator.clipboard.writeText(text)
    toasts.ok(`${what} kopiert`)
  } catch {
    toasts.error('Kopieren nicht möglich.')
  }
}

const revoking = ref<TrsCode | null>(null)
async function confirmRevoke() {
  const code = revoking.value
  revoking.value = null
  if (!code) return
  await run(`revoke:${code.id}`, async () => {
    await backend.trs.adminRevokeCode(code.id)
    toasts.ok('Code widerrufen')
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
    toasts.ok(already ? 'Hatte den Umhang schon.' : `${capeName(grantCape.value)} an ${p.name ?? 'Spieler'} vergeben`)
    await reloadPlayer()
  })
}

const revokeGrant = (capeId: string) =>
  run(`ungrant:${capeId}`, async () => {
    const p = player.value
    if (!p) return
    await backend.trs.adminRevokeGrant(p.uuid, capeId)
    toasts.ok('Umhang entzogen')
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
    toasts.ok('Spieler gesperrt')
    await loadStats()
  })
}

const unban = () =>
  run('unban', async () => {
    const p = player.value
    if (!p) return
    await backend.trs.adminUnban(p.uuid)
    toasts.ok('Sperre aufgehoben')
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
watch(tab, (t) => {
  if (t === 'capes' && !reviewCapes.value) void loadReview()
  if (t === 'codes' && !codes.value) void loadCodes()
  if (t === 'overview') void loadStats()
})
watch(list, loadReview)

const statTiles = computed(() => {
  const s = stats.value
  if (!s) return []
  return [
    { label: 'Nutzer', value: s.users.total, hint: `${s.users.activeLast24h} aktiv (24 h)` },
    { label: 'Online', value: s.users.online, hint: `${s.eventStreams} Live-Verbindungen` },
    { label: 'Wartende Umhänge', value: s.capes.pending, hint: `${s.capes.reported} gemeldet`, alert: s.capes.pending > 0 },
    { label: 'Umhänge getragen', value: s.capes.activeUsers, hint: `${s.capes.approved} Uploads freigegeben` },
    { label: 'Aktive Codes', value: s.codes.active, hint: `${s.codes.redemptions} eingelöst` },
    { label: 'Freundschaften', value: s.friendships, hint: `${s.pendingFriendRequests} offene Anfragen` },
    { label: 'Gesperrt', value: s.users.banned, hint: `${s.sessions} Sitzungen` },
  ]
})
</script>

<template>
  <div class="mx-auto max-w-5xl p-6">
    <PageHeader title="Verwaltung" subtitle="TRS-Dienste: Umhänge prüfen, Codes, Spieler und Zahlen." />

    <div v-if="!trs.isAdmin" class="card px-4 py-6 text-center text-sm text-base-400">
      Diese Seite ist nur für Admins der TRS-Dienste.
    </div>

    <template v-else>
      <div class="mb-5 flex gap-1 rounded-lg bg-base-850 p-1 text-xs" role="tablist" aria-label="Bereich">
        <button
          v-for="[key, label] in ([['overview', 'Übersicht'], ['capes', 'Umhänge prüfen'], ['codes', 'Codes'], ['players', 'Spieler']] as const)"
          :key="key"
          class="seg flex flex-1 items-center justify-center gap-1.5 rounded-md"
          :class="{ 'seg-on': tab === key }"
          role="tab"
          :aria-selected="tab === key"
          @click="tab = key"
        >
          {{ label }}
          <span v-if="key === 'capes' && stats?.capes.pending" class="rounded-full bg-redstone-500 px-1.5 text-[10px] font-bold text-white">
            {{ stats.capes.pending }}
          </span>
        </button>
      </div>

      <!-- Übersicht --------------------------------------------------------------- -->
      <section v-if="tab === 'overview'" aria-label="Übersicht">
        <div v-if="!stats" class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <div v-for="i in 7" :key="i" class="skeleton h-24" />
        </div>
        <div v-else class="grid grid-cols-2 gap-3 md:grid-cols-4" data-testid="admin-stats">
          <div v-for="t in statTiles" :key="t.label" class="card px-4 py-3" :class="{ 'border-lamp-400/40': t.alert }">
            <p class="text-xs text-base-400">{{ t.label }}</p>
            <p class="display mt-1 text-3xl tabular-nums" :class="t.alert ? 'text-lamp-300' : 'text-base-50'">{{ t.value }}</p>
            <p class="mt-0.5 text-[11px] text-base-600">{{ t.hint }}</p>
          </div>
        </div>
        <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="loadStats">Aktualisieren</button>
      </section>

      <!-- Umhänge prüfen ---------------------------------------------------------- -->
      <section v-else-if="tab === 'capes'" aria-label="Umhänge prüfen">
        <div class="mb-3 flex flex-wrap gap-1.5">
          <button
            v-for="[key, label] in lists"
            :key="key"
            class="chip"
            :class="{ 'border-redstone-500 bg-redstone-900/40 text-base-50': list === key }"
            :aria-pressed="list === key"
            @click="list = key"
          >
            {{ label }}
          </button>
        </div>
        <div v-if="!reviewCapes" class="space-y-2">
          <div v-for="i in 3" :key="i" class="skeleton h-28" />
        </div>
        <RedstoneEmpty
          v-else-if="!reviewCapes.length"
          :title="list === 'pending' ? 'Alles freigegeben – keine Umhänge warten.' : 'Hier ist nichts.'"
          compact
          :seed="0x5c"
        />
        <ul v-else class="space-y-2" data-testid="admin-capes">
          <li v-for="cape in reviewCapes" :key="cape.id" class="card flex flex-wrap items-center gap-4 px-4 py-3">
            <div class="flex items-end gap-3 rounded-lg bg-base-850 p-2">
              <CapeThumb :texture="cape.texture" :scale="cape.scale" :frames="cape.frames" :frame-time-ms="cape.frameTimeMs" :width="40" />
              <img
                v-if="cape.texture"
                :src="cape.texture"
                alt="Ganze Textur"
                class="h-16 w-32 rounded object-contain [image-rendering:pixelated]"
              />
            </div>
            <div class="min-w-0 flex-1 text-sm">
              <p class="truncate font-semibold text-base-50">{{ cape.name }}</p>
              <p class="text-xs text-base-400">
                von <strong class="text-base-200">{{ cape.owner?.name ?? 'gelöschtem Konto' }}</strong> ·
                {{ trsDate(cape.createdAt) }} · {{ cape.width }}×{{ cape.height }}
              </p>
              <p v-if="cape.reports.count" class="mt-0.5 text-xs text-warn">
                {{ cape.reports.count }}× gemeldet:
                {{ Object.entries(cape.reports.reasons).map(([r, n]) => `${reasonNames[r] ?? r} (${n})`).join(', ') }}
              </p>
              <p v-if="cape.rejectReason" class="mt-0.5 text-xs text-redstone-300">Grund: {{ cape.rejectReason }}</p>
              <p v-if="cape.reviewedBy" class="mt-0.5 text-[11px] text-base-600">Geprüft {{ trsDate(cape.reviewedAt) }}</p>
            </div>
            <div class="flex gap-2">
              <button
                v-if="cape.status !== 'approved' || cape.reports.count"
                class="btn btn-primary px-3 py-1.5 text-xs"
                :disabled="!!busy"
                @click="approve(cape)"
              >
                Freigeben
              </button>
              <button v-if="cape.status !== 'rejected'" class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="startReject(cape)">
                Ablehnen
              </button>
              <button class="btn btn-ghost px-3 py-1.5 text-xs hover:text-redstone-300" :disabled="!!busy" @click="deleting = cape">Löschen</button>
            </div>
          </li>
        </ul>
      </section>

      <!-- Codes ------------------------------------------------------------------- -->
      <section v-else-if="tab === 'codes'" aria-label="Codes" class="space-y-4">
        <form class="card grid grid-cols-1 gap-3 p-4 md:grid-cols-[2fr_1fr_1fr_1.4fr]" @submit.prevent="createCodes">
          <label class="block">
            <span class="label">Umhang</span>
            <select v-model="codeForm.capeId" class="field">
              <option value="" disabled>Umhang wählen …</option>
              <option v-for="c in grantable" :key="c.id" :value="c.id">{{ c.name }} ({{ trsUnlockLabel(c) }})</option>
            </select>
          </label>
          <label class="block">
            <span class="label">Einlösungen je Code</span>
            <input v-model.number="codeForm.maxUses" type="number" min="1" max="100000" class="field" />
          </label>
          <label class="block">
            <span class="label">Anzahl Codes</span>
            <input v-model.number="codeForm.count" type="number" min="1" max="100" class="field" />
          </label>
          <label class="block">
            <span class="label">Gültig bis (optional)</span>
            <input v-model="codeForm.expires" type="date" class="field" />
          </label>
          <label class="block md:col-span-3">
            <span class="label">Notiz (optional)</span>
            <input v-model="codeForm.note" class="field" maxlength="200" placeholder="z. B. Discord-Gewinnspiel" />
          </label>
          <div class="flex items-end">
            <button class="btn btn-primary w-full" :disabled="!!busy">{{ busy === 'codes' ? 'Erstelle …' : 'Codes erstellen' }}</button>
          </div>
          <p v-if="codeError" role="alert" class="text-xs text-redstone-300 md:col-span-4">{{ codeError }}</p>
        </form>

        <div v-if="created.length" class="card border-lamp-400/40 p-4" data-testid="admin-created-codes">
          <div class="mb-2 flex items-center gap-2">
            <p class="flex-1 text-sm font-semibold text-lamp-300">Neue Codes – nur jetzt sichtbar, gleich sichern!</p>
            <button class="btn btn-ghost px-3 py-1 text-xs" @click="copy(created.map((c) => c.code).join('\n'), 'Alle Codes')">Alle kopieren</button>
            <button class="btn btn-ghost px-3 py-1 text-xs" @click="created = []">Ausblenden</button>
          </div>
          <ul class="grid gap-1.5 sm:grid-cols-2">
            <li v-for="c in created" :key="c.id" class="flex items-center gap-2 rounded-md bg-base-850 px-3 py-1.5">
              <code class="flex-1 font-mono text-sm tracking-wider text-base-50 select-all">{{ c.code }}</code>
              <button class="btn btn-ghost px-2 py-0.5 text-[11px]" @click="copy(c.code ?? '')">Kopieren</button>
            </li>
          </ul>
        </div>

        <div v-if="!codes" class="skeleton h-40" />
        <RedstoneEmpty v-else-if="!codes.length" title="Noch keine Codes" compact :seed="0x19" />
        <div v-else class="card overflow-x-auto">
          <table class="w-full text-left text-xs">
            <thead class="text-base-400">
              <tr class="border-b border-base-800">
                <th class="px-3 py-2 font-medium">Code</th>
                <th class="px-3 py-2 font-medium">Umhang</th>
                <th class="px-3 py-2 font-medium">Eingelöst</th>
                <th class="px-3 py-2 font-medium">Gültig bis</th>
                <th class="px-3 py-2 font-medium">Notiz</th>
                <th class="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in codes" :key="c.id" class="border-b border-base-800/60 last:border-0" :class="{ 'opacity-50': c.revokedAt }">
                <td class="px-3 py-2 font-mono text-base-200">…{{ c.hint }}</td>
                <td class="px-3 py-2">{{ capeName(c.capeId) }}</td>
                <td class="px-3 py-2 tabular-nums">{{ c.uses }} / {{ c.maxUses }}</td>
                <td class="px-3 py-2">{{ c.expiresAt ? trsDate(c.expiresAt) : 'unbegrenzt' }}</td>
                <td class="max-w-48 truncate px-3 py-2 text-base-400" :title="c.note ?? ''">{{ c.note ?? '–' }}</td>
                <td class="px-3 py-2 text-right">
                  <span v-if="c.revokedAt" class="text-base-600">widerrufen</span>
                  <button v-else class="btn btn-ghost px-2 py-0.5 text-[11px] hover:text-redstone-300" :disabled="!!busy" @click="revoking = c">Widerrufen</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Spieler ----------------------------------------------------------------- -->
      <section v-else aria-label="Spieler" class="space-y-4">
        <form class="card flex flex-wrap items-center gap-2 px-3 py-2.5" @submit.prevent="lookup">
          <input v-model="query" class="field min-w-0 flex-1 py-1.5" maxlength="36" placeholder="Minecraft-Name oder UUID" aria-label="Spieler suchen" />
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!!busy">{{ busy === 'lookup' ? 'Suche …' : 'Suchen' }}</button>
          <p v-if="queryError" role="alert" class="w-full text-xs text-redstone-300">{{ queryError }}</p>
        </form>

        <div v-if="player" class="card space-y-4 p-4" data-testid="admin-player">
          <div class="flex flex-wrap items-center gap-3">
            <span class="block size-12 overflow-hidden rounded-md"><PixelIdenticon :seed="player.uuid" :letter="(player.name ?? '?').charAt(0).toUpperCase()" /></span>
            <div class="min-w-0 flex-1">
              <p class="flex items-center gap-2 text-base font-semibold text-base-50">
                {{ player.name ?? 'Unbekannt' }}
                <span v-if="player.admin" class="badge bg-redstone-900/50 text-redstone-300">Admin</span>
                <span v-if="player.banned" class="badge bg-redstone-600/30 text-redstone-300">Gesperrt</span>
                <span v-if="player.online" class="badge bg-ok/10 text-ok">Online</span>
              </p>
              <p class="font-mono text-[11px] text-base-400 select-all">{{ player.uuid }}</p>
            </div>
            <button class="btn btn-ghost px-2 py-1 text-[11px]" @click="copy(player.uuid, 'UUID')">UUID kopieren</button>
          </div>

          <dl v-if="player.known" class="grid grid-cols-2 gap-x-6 gap-y-1 text-xs md:grid-cols-4">
            <div><dt class="text-base-400">Dabei seit</dt><dd class="text-base-50">{{ trsDate(player.createdAt) }}</dd></div>
            <div><dt class="text-base-400">Letzte Anmeldung</dt><dd class="text-base-50">{{ trsDate(player.lastLoginAt) }}</dd></div>
            <div><dt class="text-base-400">Freunde</dt><dd class="text-base-50">{{ player.friends }}</dd></div>
            <div><dt class="text-base-400">Uploads / Sitzungen</dt><dd class="text-base-50">{{ player.uploads }} / {{ player.sessions }}</dd></div>
          </dl>
          <p v-else class="text-xs text-base-400">Hat die TRS-Dienste noch nie benutzt – nur eine Sperre ist möglich.</p>

          <div v-if="player.banned" class="rounded-lg border border-redstone-600/50 bg-redstone-900/30 px-3 py-2 text-xs text-redstone-300">
            Gesperrt am {{ trsDate(player.banned.bannedAt) }}{{ player.banned.reason ? ` – ${player.banned.reason}` : '' }}
          </div>

          <div v-if="player.known">
            <p class="label">Umhänge</p>
            <ul v-if="player.grantedCapes.length" class="mb-2 flex flex-wrap gap-1.5">
              <li v-for="g in player.grantedCapes" :key="g.capeId" class="chip gap-1.5">
                {{ capeName(g.capeId) }}
                <span class="text-[10px] text-base-600">{{ g.source }}</span>
                <button class="text-base-400 hover:text-redstone-300" :aria-label="`${capeName(g.capeId)} entziehen`" :disabled="!!busy" @click="revokeGrant(g.capeId)">✕</button>
              </li>
            </ul>
            <p v-else class="mb-2 text-xs text-base-600">Keine vergebenen Umhänge.</p>
            <div class="flex gap-2">
              <select v-model="grantCape" class="field w-64 py-1.5" aria-label="Umhang vergeben">
                <option value="" disabled>Umhang vergeben …</option>
                <option v-for="c in grantable" :key="c.id" :value="c.id">{{ c.name }} ({{ trsUnlockLabel(c) }})</option>
              </select>
              <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!grantCape || !!busy" @click="grant">Vergeben</button>
            </div>
          </div>

          <div class="border-t border-base-800 pt-3">
            <p class="label">Sperre</p>
            <div v-if="player.banned" class="flex gap-2">
              <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="unban">Sperre aufheben</button>
            </div>
            <div v-else-if="!player.admin" class="flex flex-wrap gap-2">
              <input v-model="banReason" class="field min-w-0 flex-1 py-1.5" maxlength="200" placeholder="Grund (optional)" aria-label="Grund der Sperre" />
              <button class="btn btn-danger px-3 py-1.5 text-xs" :disabled="!!busy" @click="banning = true">Sperren …</button>
            </div>
            <p v-else class="text-xs text-base-600">Admins können nicht gesperrt werden.</p>
          </div>
        </div>
      </section>
    </template>

    <!-- Dialoge -------------------------------------------------------------------- -->
    <BaseDialog v-if="rejecting" title="Umhang ablehnen?" @close="rejecting = null">
      <p class="mb-3 text-sm text-base-200">
        <strong class="text-base-50">{{ rejecting.name }}</strong> wird abgelehnt und von niemandem mehr getragen.
      </p>
      <label class="label" for="reject-reason">Grund (sieht der Uploader)</label>
      <input id="reject-reason" v-model="rejectReason" class="field" maxlength="200" placeholder="z. B. Urheberrecht" @keydown.enter="confirmReject" />
      <p v-if="rejectError" role="alert" class="mt-2 text-xs text-redstone-300">{{ rejectError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="rejecting = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmReject">Ablehnen</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="deleting" title="Umhang löschen?" @close="deleting = null">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ deleting.name }}</strong> von {{ deleting.owner?.name ?? 'unbekannt' }} wird endgültig
        gelöscht, auch die Datei.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="deleting = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDeleteCape">Löschen</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="revoking" title="Code widerrufen?" @close="revoking = null">
      <p class="text-sm text-base-200">
        Der Code <span class="font-mono">…{{ revoking.hint }}</span> für {{ capeName(revoking.capeId) }} kann danach nicht mehr
        eingelöst werden. Schon freigeschaltete Umhänge bleiben.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="revoking = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmRevoke">Widerrufen</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="banning && player" title="Spieler sperren?" @close="banning = false">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ player.name ?? player.uuid }}</strong> verliert sofort den Zugang zu allen TRS-Diensten:
        Sitzungen enden, Online-Status verschwindet.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="banning = false">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmBan">Sperren</button>
      </template>
    </BaseDialog>
  </div>
</template>
