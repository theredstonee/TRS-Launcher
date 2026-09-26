<script setup lang="ts">
// Spieler-Akte (/admin/players/<uuid>): Profil, Namen, aktive Strafen + Verlauf, Meldungen gegen/von
// ihm mit Melder-Score, Umhänge/Kosmetik, offene Welten, interne Notizen und Schnellaktionen.
const route = useRoute()
const { a, m, fill, when, rel, day, actor } = useAdminText()
const { api } = useAdmin()

const uuid = computed(() => String(route.params.uuid ?? '').replace(/-/g, '').toLowerCase())
const file = ref<PlayerFile | null>(null)
const error = ref('')
const missing = ref(false)
const sanctionKind = ref<SanctionKind | null>(null)
const copied = ref(false)

async function load() {
  error.value = ''
  missing.value = false
  try {
    file.value = (await api<{ file: PlayerFile }>(`/v1/admin/players/${uuid.value}`)).file
  } catch (e) {
    file.value = null
    if ((e as { statusCode?: number }).statusCode === 404) missing.value = true
    else error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}
watch(uuid, load, { immediate: true })

const active = computed(() => file.value?.sanctions.filter((s) => s.status === 'active') ?? [])
const history = computed(() => file.value?.sanctions.filter((s) => s.status !== 'active') ?? [])

// --- Notizen ---------------------------------------------------------------------------------------------
const note = ref('')
const noteBusy = ref(false)
async function addNote() {
  const text = note.value.trim()
  if (!text || !file.value) return
  noteBusy.value = true
  try {
    const r = await api<{ notes: PlayerFile['notes'] }>(`/v1/admin/players/${uuid.value}/notes`, { method: 'POST', body: { text: text.slice(0, 2000) } })
    file.value.notes = r.notes
    note.value = ''
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    noteBusy.value = false
  }
}
const deleting = ref<number | null>(null)
async function deleteNote() {
  if (deleting.value === null || !file.value) return
  try {
    const r = await api<{ notes: PlayerFile['notes'] }>(`/v1/admin/players/${uuid.value}/notes/${deleting.value}`, { method: 'DELETE' })
    file.value.notes = r.notes
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    deleting.value = null
  }
}

async function copyUuid() {
  try {
    await navigator.clipboard.writeText(uuid.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // UUID steht markierbar da.
  }
}
function localUrl(url: string): string {
  try {
    const u = new URL(url, location.origin)
    return u.pathname + u.search
  } catch {
    return url
  }
}
const QUICK: SanctionKind[] = ['warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban', 'account_ban']
</script>

<template>
  <div class="adm-page">
    <NuxtLink to="/admin/players" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ a.file.back }}</NuxtLink>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="missing" class="adm-empty mt-6"><SiteIcon name="user" class="size-6" />{{ a.file.notFound }}</div>
    <div v-else-if="!file && !error" class="mt-6 grid gap-4 lg:grid-cols-3">
      <div class="skeleton h-40 rounded-xl lg:col-span-3" />
      <div v-for="i in 3" :key="i" class="skeleton h-64 rounded-xl" />
    </div>

    <template v-if="file">
      <!-- Profil -->
      <section class="card mt-4 flex flex-wrap items-center gap-5 p-5">
        <PlayerHead :uuid="file.player.uuid" :name="file.player.name" :size="72" />
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="adm-title">{{ file.player.name || a.common.unknown }}</h1>
            <span class="tone" :class="file.player.online ? 'tone-ok' : 'tone-muted'">{{ file.player.online ? a.file.online : a.file.offline }}</span>
            <span v-if="file.player.role" class="tone tone-info">{{ a.role[file.player.role] }}</span>
            <span v-if="file.player.banned" class="tone tone-danger">{{ a.file.banned }}</span>
          </div>
          <button type="button" class="adm-mono mt-1 flex items-center gap-1.5 text-base-400 hover:text-base-50" :title="a.file.copyUuid" @click="copyUuid">
            {{ file.player.uuid }}<SiteIcon :name="copied ? 'check' : 'copy'" class="size-3.5" />
          </button>
          <p v-if="!file.player.known" class="mt-2 text-sm text-lamp-300">{{ a.file.unknownAccount }}</p>
          <dl v-else class="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-sm">
            <div><dt class="inline text-base-400">{{ a.players.firstLogin }}: </dt><dd class="inline text-base-200">{{ day(file.player.firstLoginAt) }}</dd></div>
            <div><dt class="inline text-base-400">{{ a.players.lastLogin }}: </dt><dd class="inline text-base-200" :title="when(file.player.lastLoginAt)">{{ rel(file.player.lastLoginAt) }}</dd></div>
            <div class="text-base-300">{{ fill(a.file.friends, { n: file.player.friends }) }}</div>
            <div class="text-base-300">{{ fill(a.file.sessions, { n: file.player.sessions }) }}</div>
          </dl>
        </div>
        <div class="w-full sm:w-auto">
          <p class="label">{{ a.file.quick }}</p>
          <p v-if="!file.can.sanction" class="text-xs text-base-400">{{ a.file.cannot[file.can.reason ?? ''] }}</p>
          <div v-else class="flex flex-wrap gap-1.5 sm:max-w-sm">
            <button
              v-for="k in QUICK"
              :key="k"
              type="button"
              class="tone cursor-pointer px-2.5 py-1 text-xs hover:brightness-125 disabled:cursor-not-allowed disabled:opacity-40"
              :class="kindTone(k)"
              :disabled="!file.can.limits.kinds.includes(k)"
              @click="sanctionKind = k"
            >
              + {{ a.kinds[k] }}
            </button>
          </div>
        </div>
      </section>

      <div class="mt-4 grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div class="min-w-0 space-y-4">
          <!-- Strafen -->
          <section class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="gavel" class="size-4 text-base-400" />{{ a.file.activeSanctions }}
              <span class="ml-auto text-xs font-normal text-base-400">{{ fill(a.file.warnings, file.warnings) }}</span>
            </h2>
            <p v-if="!active.length" class="mt-3 text-sm text-base-400">{{ a.file.noActive }}</p>
            <div v-else class="mt-3 space-y-2">
              <SanctionCard v-for="s in active" :key="s.id" :sanction="s" @changed="load" />
            </div>
          </section>
          <section class="card p-5">
            <h2 class="section-title">{{ a.file.history }}</h2>
            <p v-if="!history.length" class="mt-3 text-sm text-base-400">{{ a.file.noHistory }}</p>
            <div v-else class="mt-3 space-y-2">
              <SanctionCard v-for="s in history" :key="s.id" :sanction="s" @changed="load" />
            </div>
          </section>

          <!-- Meldungen -->
          <section class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="flag" class="size-4 text-base-400" />{{ a.file.reportsAgainst }}</h2>
            <p class="mt-1 text-xs text-base-400">{{ fill(a.file.counts, file.reports.against.counts) }}</p>
            <ul class="mt-3 divide-y divide-base-800 text-sm">
              <li v-for="r in file.reports.against.recent" :key="r.id" class="flex flex-wrap items-center gap-2 py-2">
                <span v-if="r.priority === 'high' && r.status !== 'resolved'" class="tone tone-danger">{{ a.reports.high }}</span>
                <NuxtLink :to="`/admin/reports/${r.id}`" class="font-medium text-base-50 hover:underline">{{ m.admin.mod.reasons[r.reason] }}</NuxtLink>
                <span class="text-base-400">{{ m.admin.mod.kinds[r.kind] }} · {{ r.status === 'resolved' && r.outcome ? m.admin.mod.outcome[r.outcome] : m.admin.mod.status[r.status] }}</span>
                <span v-if="r.preview" class="w-full truncate text-xs text-base-300">„{{ r.preview }}“</span>
                <span class="ml-auto text-xs text-base-400">{{ rel(r.createdAt) }}</span>
              </li>
            </ul>
          </section>
          <section class="card p-5">
            <h2 class="section-title">{{ a.file.reportsFiled }}</h2>
            <p class="mt-1 text-xs text-base-400">{{ fill(a.file.counts, file.reports.filed.counts) }}</p>
            <p class="mt-2 text-sm">
              <span class="text-base-400">{{ a.file.reporterScore }}: </span>
              <span class="text-base-50">{{ file.reports.reporterScore.score === null ? a.file.scoreNone : fill(a.file.scoreValue, { score: file.reports.reporterScore.score }) }}</span>
              <span v-if="file.reports.reporterScore.low" class="tone tone-warn ml-2">{{ a.file.lowTrust }}</span>
            </p>
            <ul class="mt-3 divide-y divide-base-800 text-sm">
              <li v-for="r in file.reports.filed.recent" :key="r.id" class="flex flex-wrap items-center gap-2 py-2">
                <NuxtLink :to="`/admin/reports/${r.id}`" class="text-base-50 hover:underline">{{ m.admin.mod.reasons[r.reason] }}</NuxtLink>
                <span class="text-base-400">{{ fill(m.admin.mod.against, { name: r.target?.name || m.admin.mod.unknown }) }}</span>
                <span class="ml-auto text-xs text-base-400">{{ r.status === 'resolved' && r.outcome ? m.admin.mod.outcome[r.outcome] : m.admin.mod.status[r.status] }}</span>
              </li>
            </ul>
          </section>
        </div>

        <aside class="space-y-4">
          <!-- Notizen -->
          <section class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="note" class="size-4 text-base-400" />{{ a.file.notes }}</h2>
            <form class="mt-3" @submit.prevent="addNote">
              <textarea v-model="note" class="field min-h-20" maxlength="2000" :placeholder="a.file.notePlaceholder" :aria-label="a.file.notes" />
              <button type="submit" class="btn btn-ghost mt-2 w-full" :disabled="noteBusy || !note.trim()">{{ a.file.addNote }}</button>
            </form>
            <p v-if="!file.notes.length" class="mt-3 text-xs text-base-400">{{ a.file.noNotes }}</p>
            <ul v-else class="mt-3 space-y-2">
              <li v-for="n in file.notes" :key="n.id" class="rounded-md bg-base-950 px-3 py-2 text-sm">
                <p class="flex items-center gap-2 text-xs text-base-400">{{ actor(n.actor) }} · {{ when(n.at) }}
                  <button v-if="n.deletable" type="button" class="ml-auto text-base-400 hover:text-redstone-300" :aria-label="a.file.deleteNote" @click="deleting = n.id"><SiteIcon name="trash" class="size-3.5" /></button>
                </p>
                <p class="adm-note mt-1 text-base-100">{{ n.text }}</p>
              </li>
            </ul>
          </section>

          <section class="card p-5">
            <h2 class="section-title">{{ a.file.names }}</h2>
            <p v-if="file.names.length <= 1" class="mt-2 text-xs text-base-400">{{ a.file.noNames }}</p>
            <ul v-else class="mt-2 space-y-1 text-sm">
              <li v-for="n in file.names" :key="n.name" class="flex gap-2"><span class="text-base-50">{{ n.name }}</span><span class="ml-auto text-xs text-base-400">{{ day(n.firstSeen) }} – {{ day(n.lastSeen) }}</span></li>
            </ul>
          </section>

          <section class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="cape" class="size-4 text-base-400" />{{ a.file.uploads }}</h2>
            <p v-if="!file.capes.length && !file.cosmetics.length" class="mt-2 text-xs text-base-400">{{ a.file.noUploads }}</p>
            <ul class="mt-3 space-y-2">
              <li v-for="c in file.capes" :key="`c${c.id}`" class="flex items-center gap-3">
                <span class="grid w-12 shrink-0 place-items-center rounded-md bg-base-950 py-1.5">
                  <CapeThumb :texture="localUrl(c.url)" :scale="c.scale" :frames="c.frames" :frame-time-ms="c.frameTimeMs" :width="26" />
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm text-base-50">{{ c.name }}</span>
                  <span class="text-xs text-base-400">{{ a.file.source[c.source] }} · {{ day(c.createdAt) }}</span>
                </span>
                <span class="tone" :class="c.status === 'approved' ? 'tone-ok' : c.status === 'pending' ? 'tone-warn' : 'tone-danger'">{{ a.uploads.filters[c.status] ?? c.status }}</span>
                <span v-if="c.reports" class="tone tone-warn"><SiteIcon name="flag" class="size-3" />{{ c.reports }}</span>
              </li>
              <li v-for="c in file.cosmetics" :key="`k${c.id}`" class="flex items-center gap-3">
                <span class="grid w-12 shrink-0 place-items-center rounded-md bg-base-950 py-2 text-[10px] text-base-400 uppercase">{{ c.slot }}</span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm text-base-50">{{ c.name }}</span>
                  <span class="text-xs text-base-400">{{ a.file.source[c.source] }}<template v-if="c.createdAt"> · {{ day(c.createdAt) }}</template></span>
                </span>
                <span class="tone" :class="c.status === 'approved' ? 'tone-ok' : c.status === 'pending' ? 'tone-warn' : 'tone-danger'">{{ a.uploads.filters[c.status] ?? c.status }}</span>
              </li>
            </ul>
          </section>

          <section class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="world" class="size-4 text-base-400" />{{ a.file.worlds }}</h2>
            <p v-if="!file.worlds.length" class="mt-2 text-xs text-base-400">{{ a.file.noWorlds }}</p>
            <ul v-else class="mt-2 space-y-2 text-sm">
              <li v-for="w in file.worlds" :key="w.id">
                <p class="text-base-50">{{ w.name }} <span class="text-xs text-base-400">· {{ w.mcVersion }} {{ w.loader }}</span></p>
                <p class="text-xs text-base-400">{{ a.worlds.host }}: {{ w.host.name }} · {{ fill(a.worlds.players, { n: w.players, max: w.maxPlayers }) }}</p>
              </li>
            </ul>
          </section>
        </aside>
      </div>
    </template>

    <SanctionDialog v-if="sanctionKind && file" :player="{ uuid: file.player.uuid, name: file.player.name }" :kind="sanctionKind" @close="sanctionKind = null" @done="sanctionKind = null; load()" />
    <AdminConfirm v-if="deleting !== null" :title="a.file.deleteNote" danger @cancel="deleting = null" @confirm="deleteNote" />
  </div>
</template>
