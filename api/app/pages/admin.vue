<script setup lang="ts">
// Admin-Bereich: Anmeldung per Code, der im TRS Launcher bestätigt wird; danach Übersicht,
// Umhang-Prüfung, Codes und Spieler – alles über die bestehenden /v1/admin-Endpunkte.

useHead({ title: 'Admin', meta: [{ name: 'robots', content: 'noindex, nofollow' }] })

const { m, fill, date, lang } = useLang()
const { session, load, api, logout } = useAdmin()

type Tab = 'overview' | 'capes' | 'codes' | 'players'
const tab = ref<Tab>('overview')
const ready = ref(false)
const failure = ref('')

function fail(e: unknown) {
  failure.value = fill(m.value.admin.failed, { error: apiMessage(e) })
}

/** Texturen immer über diese Seite laden (Cookie + CSP), auch wenn die API eine andere Adresse nennt. */
function localUrl(url: string): string {
  try {
    const u = new URL(url, location.origin)
    return u.pathname + u.search
  } catch {
    return url
  }
}

// --- Anmeldung ----------------------------------------------------------------------------
const code = ref<{ code: string, pollSecret: string, expiresAt: string } | null>(null)
const expired = ref(false)
const starting = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
}

async function startLogin() {
  starting.value = true
  failure.value = ''
  expired.value = false
  try {
    code.value = await api('/v1/web-login/start', { method: 'POST' })
    stopPolling()
    pollTimer = setInterval(poll, 2000)
  } catch (e) {
    fail(e)
  } finally {
    starting.value = false
  }
}

async function poll() {
  if (!code.value) return
  try {
    const r = await api<{ status: 'pending' | 'expired' | 'approved', name?: string, csrf?: string }>('/v1/web-login/poll', {
      method: 'POST',
      body: { pollSecret: code.value.pollSecret },
    })
    if (r.status === 'approved') {
      stopPolling()
      code.value = null
      await load()
      await refreshAll()
    } else if (r.status === 'expired') {
      stopPolling()
      expired.value = true
    }
  } catch (e) {
    stopPolling()
    fail(e)
  }
}

onMounted(async () => {
  if (await load()) await refreshAll()
  ready.value = true
})
onBeforeUnmount(stopPolling)

async function signOut() {
  await logout().catch(() => {})
  code.value = null
}

// --- Übersicht -------------------------------------------------------------------------------
interface Stats {
  users: { total: number, banned: number, activeLast24h: number, online: number }
  capes: { builtin: number, approved: number, pending: number, rejected: number, reported: number, activeUsers: number }
  codes: { active: number, redemptions: number }
  friendships: number
}
const stats = ref<Stats | null>(null)
const statTiles = computed(() => {
  const s = stats.value
  if (!s) return []
  return [
    { label: m.value.admin.stats.users, value: s.users.total },
    { label: m.value.admin.stats.activeWeek, value: s.users.activeLast24h },
    { label: m.value.admin.stats.online, value: s.users.online, live: true },
    { label: m.value.admin.stats.capes, value: s.capes.builtin + s.capes.approved },
    { label: m.value.admin.stats.pending, value: s.capes.pending, warn: s.capes.pending > 0 },
    { label: m.value.admin.stats.reports, value: s.capes.reported, warn: s.capes.reported > 0 },
    { label: m.value.admin.stats.codes, value: s.codes.active },
  ]
})

// --- Umhänge --------------------------------------------------------------------------------
type ReviewStatus = 'pending' | 'reported' | 'approved' | 'rejected'
const REVIEW_STATUSES: ReviewStatus[] = ['pending', 'reported', 'approved', 'rejected']
const reviewStatus = ref<ReviewStatus>('pending')
const reviewCapes = ref<AdminCape[]>([])
const busy = ref('')
/** Offener Prüf-Dialog: Position in `reviewCapes`, `null` = zu. */
const reviewIndex = ref<number | null>(null)
const reviewCape = computed(() => (reviewIndex.value === null ? null : reviewCapes.value[reviewIndex.value] ?? null))
const reviewError = ref('')
const locale = computed(() => (lang.value === 'en' ? 'en-GB' : lang.value))

async function loadCapes() {
  const r = await api<{ capes: AdminCape[] }>(`/v1/admin/capes?status=${reviewStatus.value}`)
  reviewCapes.value = r.capes
}

function openReview(i: number) {
  reviewError.value = ''
  reviewIndex.value = i
}

function stepReview(dir: 1 | -1) {
  if (reviewIndex.value === null) return
  const next = reviewIndex.value + dir
  if (next < 0 || next >= reviewCapes.value.length) return
  reviewError.value = ''
  reviewIndex.value = next
}

/**
 * Entscheidung im Dialog. Danach Liste + Zahlen neu laden und zum nächsten Umhang der Liste
 * weitergehen (am Ende zum letzten übrigen); ist keiner mehr übrig, schließt der Dialog.
 */
async function capeAction(action: 'approve' | 'reject' | 'delete', reason?: string) {
  const c = reviewCape.value
  if (!c || busy.value) return
  const i = reviewIndex.value!
  const nextId = reviewCapes.value[i + 1]?.id ?? null
  busy.value = c.id
  reviewError.value = ''
  try {
    if (action === 'delete') await api(`/v1/admin/capes/${c.id}`, { method: 'DELETE' })
    else if (action === 'reject') await api(`/v1/admin/capes/${c.id}/reject`, { method: 'POST', body: reason ? { reason } : {} })
    else await api(`/v1/admin/capes/${c.id}/approve`, { method: 'POST' })
    await Promise.all([loadCapes(), loadStats()])
    const rest = reviewCapes.value.filter((x) => x.id !== c.id)
    const target = rest.find((x) => x.id === nextId) ?? rest[rest.length - 1]
    reviewIndex.value = target ? reviewCapes.value.findIndex((x) => x.id === target.id) : null
  } catch (e) {
    reviewError.value = fill(m.value.admin.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}

// --- Codes ----------------------------------------------------------------------------------
interface CodeView {
  id: number
  hint: string
  capeId: string | null
  cosmeticId: string | null
  maxUses: number
  uses: number
  expiresAt: string | null
  revokedAt: string | null
  note: string | null
  createdAt: string
}
const codes = ref<CodeView[]>([])
const catalog = ref<SiteCape[]>([])
const lockedCapes = computed(() => catalog.value.filter((c) => c.unlock !== 'free'))
const form = reactive({ capeId: '', count: 1, maxUses: 1, note: '' })
const created = ref<string[]>([])

async function loadCodes() {
  const [r, capes] = await Promise.all([api<{ codes: CodeView[] }>('/v1/admin/codes'), api<{ capes: SiteCape[] }>('/v1/site/capes')])
  codes.value = r.codes
  catalog.value = capes.capes
  if (!form.capeId && lockedCapes.value.length) form.capeId = lockedCapes.value[0]!.id
}

async function createCodes() {
  failure.value = ''
  busy.value = 'codes'
  try {
    const r = await api<{ codes: (CodeView & { code: string })[] }>('/v1/admin/codes', {
      method: 'POST',
      body: {
        capeId: form.capeId,
        count: Math.min(100, Math.max(1, Math.round(form.count))),
        maxUses: Math.min(100000, Math.max(1, Math.round(form.maxUses))),
        ...(form.note.trim() ? { note: form.note.trim().slice(0, 200) } : {}),
      },
    })
    created.value = r.codes.map((c) => c.code)
    await loadCodes()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

async function revoke(c: CodeView) {
  busy.value = `code-${c.id}`
  try {
    await api(`/v1/admin/codes/${c.id}`, { method: 'DELETE' })
    await loadCodes()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

const copiedCodes = ref(false)
async function copyCodes() {
  try {
    await navigator.clipboard.writeText(created.value.join('\n'))
    copiedCodes.value = true
    setTimeout(() => (copiedCodes.value = false), 1600)
  } catch {
    // Codes stehen markierbar in der Liste.
  }
}

// --- Spieler --------------------------------------------------------------------------------
interface AdminUser {
  uuid: string
  name: string
  known: boolean
  admin: boolean
  banned: { reason: string | null, bannedAt: string, bannedBy: string } | null
  createdAt: string | null
  lastLoginAt: string | null
  activeCapeId: string | null
  grantedCapes: { capeId: string, source: string, grantedAt: string }[]
  friends: number
  online: boolean
}
const query = ref('')
const player = ref<AdminUser | null>(null)
const playerMissing = ref(false)
const banReason = ref('')
const grantCape = ref('')

async function findPlayer() {
  const q = query.value.trim()
  if (!q) return
  failure.value = ''
  playerMissing.value = false
  try {
    const r = await api<{ user: AdminUser }>(`/v1/admin/users/${encodeURIComponent(q)}`)
    player.value = r.user
    if (!catalog.value.length) catalog.value = (await api<{ capes: SiteCape[] }>('/v1/site/capes')).capes
    grantCape.value = lockedCapes.value[0]?.id ?? ''
  } catch (e) {
    player.value = null
    if ((e as { statusCode?: number }).statusCode === 404) playerMissing.value = true
    else fail(e)
  }
}

async function playerAction(action: 'ban' | 'unban' | 'grant') {
  const p = player.value
  if (!p) return
  busy.value = 'player'
  failure.value = ''
  try {
    if (action === 'ban') await api(`/v1/admin/users/${p.uuid}/ban`, { method: 'POST', body: banReason.value.trim() ? { reason: banReason.value.trim().slice(0, 200) } : {} })
    else if (action === 'unban') await api(`/v1/admin/users/${p.uuid}/ban`, { method: 'DELETE' })
    else await api(`/v1/admin/users/${p.uuid}/capes`, { method: 'POST', body: { capeId: grantCape.value } })
    query.value = p.uuid
    await findPlayer()
  } catch (e) {
    fail(e)
  } finally {
    busy.value = ''
  }
}

// --- Laden ----------------------------------------------------------------------------------
async function loadStats() {
  stats.value = await api<Stats>('/v1/admin/stats')
}

async function refreshAll() {
  failure.value = ''
  try {
    await loadStats()
    if (tab.value === 'capes') await loadCapes()
    if (tab.value === 'codes') await loadCodes()
  } catch (e) {
    fail(e)
  }
}

watch(tab, () => void refreshAll())
watch(reviewStatus, () => {
  reviewIndex.value = null
  void loadCapes().catch(fail)
})
watch(tab, () => (reviewIndex.value = null))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <div v-if="!ready" class="skeleton h-40 rounded-xl" />

    <!-- Anmeldung -->
    <section v-else-if="!session" class="mx-auto max-w-lg">
      <h1 class="display text-5xl text-base-50">{{ m.admin.title }}</h1>
      <p class="mt-3 text-base-400">{{ m.admin.lead }}</p>

      <div class="card mt-8 p-6">
        <template v-if="code && !expired">
          <p class="text-xs tracking-[0.18em] text-base-400 uppercase">{{ m.admin.code }}</p>
          <p class="login-code display mt-2 select-all">{{ code.code }}</p>
          <ol class="mt-6 space-y-2 text-sm text-base-200">
            <li v-for="(s, i) in m.admin.steps" :key="i" class="flex gap-3">
              <span class="step">{{ i + 1 }}</span><span>{{ s }}</span>
            </li>
          </ol>
          <p class="mt-6 flex items-center gap-2 text-sm text-lamp-300">
            <span class="size-2 animate-lamp bg-lamp-400" />{{ m.admin.waiting }}
          </p>
        </template>
        <template v-else>
          <p v-if="expired" class="mb-4 text-sm text-lamp-300">{{ m.admin.expired }}</p>
          <button type="button" class="btn btn-primary h-12 w-full text-base" :disabled="starting" @click="startLogin">
            <SiteIcon name="user" class="size-5" />{{ expired ? m.admin.newCode : m.admin.start }}
          </button>
        </template>
      </div>
      <p v-if="failure" role="alert" class="mt-4 text-sm text-redstone-300">{{ failure }}</p>
    </section>

    <!-- Bereich -->
    <section v-else>
      <header class="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 class="display text-4xl text-base-50">{{ m.admin.title }}</h1>
          <p class="mt-1 text-sm text-base-400">{{ fill(m.admin.signedInAs, { name: session.name }) }}</p>
        </div>
        <button type="button" class="btn btn-ghost" @click="signOut"><SiteIcon name="logout" class="size-4" />{{ m.admin.logout }}</button>
      </header>

      <nav class="mt-8 flex gap-1 overflow-x-auto border-b border-base-800" aria-label="Admin">
        <button
          v-for="t in (['overview', 'capes', 'codes', 'players'] as const)"
          :key="t"
          type="button"
          class="admin-tab"
          :class="{ 'admin-tab-on': tab === t }"
          @click="tab = t"
        >
          {{ m.admin.tabs[t] }}
          <span v-if="t === 'capes' && stats && stats.capes.pending + stats.capes.reported > 0" class="badge ml-1 bg-lamp-400 text-base-950">
            {{ stats.capes.pending + stats.capes.reported }}
          </span>
        </button>
      </nav>

      <p v-if="failure" role="alert" class="mt-4 text-sm text-redstone-300">{{ failure }}</p>

      <!-- Übersicht -->
      <div v-if="tab === 'overview'" class="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        <div v-for="s in statTiles" :key="s.label" class="card p-5" :class="{ 'tile-warn': s.warn }">
          <p class="text-xs text-base-400">{{ s.label }}</p>
          <p class="display mt-2 text-4xl tabular-nums text-base-50">
            <span v-if="s.live" class="mr-2 inline-block size-2.5 animate-lamp bg-ok align-middle" />{{ s.value }}
          </p>
        </div>
      </div>

      <!-- Umhänge -->
      <div v-else-if="tab === 'capes'" class="mt-8">
        <div class="seg-row flex-wrap">
          <button
            v-for="st in REVIEW_STATUSES"
            :key="st"
            type="button"
            class="seg-btn"
            :class="{ 'seg-btn-on': reviewStatus === st }"
            :aria-pressed="reviewStatus === st"
            @click="reviewStatus = st"
          >
            {{ m.admin.review[st] }}
          </button>
        </div>
        <p v-if="!reviewCapes.length" class="mt-8 text-base-400">{{ m.admin.review.none }}</p>
        <ul class="mt-6 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <li v-for="(c, i) in reviewCapes" :key="c.id">
            <button
              type="button"
              class="card card-hover flex h-full w-full gap-5 p-5 text-left"
              @click="openReview(i)"
            >
              <span class="grid w-24 shrink-0 place-items-center rounded-lg bg-base-950 py-3">
                <CapeThumb :texture="localUrl(c.url)" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="50" />
              </span>
              <span class="block min-w-0 flex-1">
                <span class="block truncate font-semibold text-base-50">{{ c.name || c.id }}</span>
                <span class="block text-xs text-base-400">{{ c.owner?.name || '–' }} · {{ date(c.createdAt) }}</span>
                <span class="mt-2 flex flex-wrap gap-1.5">
                  <span class="chip tabular-nums">{{ c.width }}×{{ c.height }}</span>
                  <span v-if="c.frames > 1" class="chip tabular-nums">{{ fill(m.admin.review.framesCount, { n: c.frames }) }}</span>
                  <span class="chip tabular-nums">{{ formatBytes(c.bytes, locale) }}</span>
                  <span v-if="c.reports.count" class="badge bg-lamp-900 text-lamp-300">
                    <SiteIcon name="flag" class="size-3" />{{ c.reports.count }}
                  </span>
                </span>
                <span class="mt-3 inline-flex items-center gap-1.5 text-xs font-medium text-redstone-300">
                  {{ m.admin.review.open }}<SiteIcon name="arrow" class="size-3.5" />
                </span>
              </span>
            </button>
          </li>
        </ul>

        <CapeReviewDialog
          v-if="reviewCape"
          :cape="reviewCape"
          :index="reviewIndex ?? 0"
          :total="reviewCapes.length"
          :busy="busy !== ''"
          :error="reviewError"
          @close="reviewIndex = null"
          @prev="stepReview(-1)"
          @next="stepReview(1)"
          @approve="capeAction('approve')"
          @reject="(reason) => capeAction('reject', reason)"
          @delete="capeAction('delete')"
        />
      </div>

      <!-- Codes -->
      <div v-else-if="tab === 'codes'" class="mt-8 grid gap-8 lg:grid-cols-[22rem_1fr]">
        <form class="card h-fit p-5" @submit.prevent="createCodes">
          <h2 class="font-semibold text-base-50">{{ m.admin.codes.create }}</h2>
          <label class="label mt-4" for="code-cape">{{ m.admin.codes.cape }}</label>
          <select id="code-cape" v-model="form.capeId" class="field" required>
            <option v-for="c in lockedCapes" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <div class="mt-3 grid grid-cols-2 gap-3">
            <div>
              <label class="label" for="code-count">{{ m.admin.codes.count }}</label>
              <input id="code-count" v-model.number="form.count" type="number" min="1" max="100" class="field" required />
            </div>
            <div>
              <label class="label" for="code-uses">{{ m.admin.codes.uses }}</label>
              <input id="code-uses" v-model.number="form.maxUses" type="number" min="1" max="100000" class="field" required />
            </div>
          </div>
          <label class="label mt-3" for="code-note">{{ m.admin.codes.note }}</label>
          <input id="code-note" v-model="form.note" class="field" maxlength="200" />
          <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'codes' || !form.capeId">{{ m.admin.codes.create }}</button>

          <div v-if="created.length" class="mt-5 border-t border-base-800 pt-4">
            <p class="text-xs text-lamp-300">{{ m.admin.codes.created }}</p>
            <pre class="codes-out mt-2 select-all">{{ created.join('\n') }}</pre>
            <button type="button" class="btn btn-ghost mt-2 w-full" @click="copyCodes">
              <SiteIcon :name="copiedCodes ? 'check' : 'copy'" class="size-4" />{{ copiedCodes ? m.common.copied : m.common.copy }}
            </button>
          </div>
        </form>

        <div class="overflow-x-auto">
          <p v-if="!codes.length" class="text-base-400">{{ m.admin.codes.none }}</p>
          <table v-else class="w-full text-left text-sm">
            <thead class="text-xs text-base-400">
              <tr><th class="py-2 pr-4">…</th><th class="py-2 pr-4">{{ m.admin.codes.cape }}</th><th class="py-2 pr-4" /><th class="py-2 pr-4">{{ m.admin.codes.note }}</th><th /></tr>
            </thead>
            <tbody class="divide-y divide-base-800">
              <tr v-for="c in codes" :key="c.id" :class="{ 'opacity-45': c.revokedAt || c.uses >= c.maxUses }">
                <td class="py-2.5 pr-4 font-mono text-base-50">…{{ c.hint }}</td>
                <td class="py-2.5 pr-4">{{ c.capeId ?? c.cosmeticId }}</td>
                <td class="py-2.5 pr-4 tabular-nums text-base-400">{{ fill(m.admin.codes.uses2, { uses: c.uses, max: c.maxUses }) }}</td>
                <td class="max-w-56 truncate py-2.5 pr-4 text-base-400">{{ c.note }}</td>
                <td class="py-2.5 text-right">
                  <button v-if="!c.revokedAt" type="button" class="btn btn-danger py-1 text-xs" :disabled="busy === `code-${c.id}`" @click="revoke(c)">
                    {{ m.admin.codes.revoke }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Spieler -->
      <div v-else class="mt-8 max-w-2xl">
        <form class="flex gap-2" @submit.prevent="findPlayer">
          <input v-model="query" class="field" maxlength="36" :placeholder="m.admin.players.search" />
          <button type="submit" class="btn btn-primary">{{ m.admin.players.find }}</button>
        </form>
        <p v-if="playerMissing" class="mt-4 text-base-400">{{ m.admin.players.notFound }}</p>
        <div v-if="player" class="card mt-6 p-5">
          <div class="flex flex-wrap items-center gap-3">
            <p class="display text-2xl text-base-50">{{ player.name }}</p>
            <span v-if="player.admin" class="badge bg-redstone-900 text-redstone-300">Admin</span>
            <span v-if="player.online" class="badge bg-ok/15 text-ok">online</span>
            <span v-if="player.banned" class="badge bg-redstone-500 text-white">banned</span>
          </div>
          <p class="mt-1 font-mono text-xs text-base-400">{{ player.uuid }}</p>
          <dl class="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
            <dt class="text-base-400">Login</dt><dd>{{ date(player.lastLoginAt) || '–' }}</dd>
            <dt class="text-base-400">{{ m.admin.codes.cape }}</dt><dd>{{ player.activeCapeId ?? '–' }}</dd>
            <dt class="text-base-400">Grants</dt><dd>{{ player.grantedCapes.map((g) => g.capeId).join(', ') || '–' }}</dd>
          </dl>
          <div v-if="!player.admin" class="mt-5 flex flex-wrap gap-2 border-t border-base-800 pt-5">
            <template v-if="player.banned">
              <button type="button" class="btn btn-ghost" :disabled="busy === 'player'" @click="playerAction('unban')">{{ m.admin.players.unban }}</button>
            </template>
            <template v-else>
              <input v-model="banReason" class="field flex-1" maxlength="200" :placeholder="m.admin.review.reason" />
              <button type="button" class="btn btn-danger" :disabled="busy === 'player'" @click="playerAction('ban')">{{ m.admin.players.ban }}</button>
            </template>
          </div>
          <div v-if="player.known" class="mt-3 flex flex-wrap gap-2">
            <select v-model="grantCape" class="field flex-1">
              <option v-for="c in lockedCapes" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
            <button type="button" class="btn btn-ghost" :disabled="busy === 'player' || !grantCape" @click="playerAction('grant')">{{ m.admin.players.grant }}</button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-code {
  font-size: 3rem;
  letter-spacing: 0.12em;
  color: var(--color-lamp-300);
  text-shadow: 0 0 24px color-mix(in srgb, var(--color-lamp-400) 45%, transparent);
}
.step {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 1.5rem;
  height: 1.5rem;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--color-base-50);
  background: var(--color-redstone-600);
}
.admin-tab {
  display: inline-flex;
  align-items: center;
  padding: 0.75rem 1rem;
  font-size: 0.875rem;
  color: var(--color-base-400);
  white-space: nowrap;
}
.admin-tab:hover {
  color: var(--color-base-50);
}
.admin-tab-on {
  color: var(--color-base-50);
  box-shadow: inset 0 -2px 0 var(--color-redstone-500);
}
.tile-warn {
  border-color: color-mix(in srgb, var(--color-lamp-400) 55%, transparent);
}
.seg-row {
  display: inline-flex;
  gap: 0.25rem;
  padding: 0.25rem;
  border-radius: 0.5rem;
  background: var(--color-base-900);
  border: 1px solid var(--color-base-800);
}
.seg-btn {
  padding: 0.375rem 0.875rem;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  color: var(--color-base-400);
}
.seg-btn-on {
  color: var(--color-base-50);
  background: var(--color-base-700);
}
.codes-out {
  max-height: 12rem;
  overflow: auto;
  padding: 0.75rem;
  border-radius: 0.375rem;
  font-family: var(--font-mono);
  font-size: 0.8125rem;
  color: var(--color-lamp-300);
  background: var(--color-base-950);
}
</style>
