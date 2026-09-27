<script setup lang="ts">
// Bewerbung im Detail (§24.3): Antworten, Abstimmung (Daumen + Kommentar), interne Notizen, Verlauf,
// Strafverlauf (nur mit players.view), Status + Antwort an den Bewerber, optional Rolle bei Annahme.
const { a, fill, when, rel, actor } = useAdminText()
const { t, lang } = useTeamText()
const { api, can, session } = useAdmin()
const route = useRoute()
const id = computed(() => String(route.params.id))

const app = ref<AdminApplicationDetail | null>(null)
const error = ref('')
const busy = ref(false)
const actionError = ref('')

const decision = reactive({ status: 'review' as ApplicationStatus, response: '', grantRole: true })
const vote = reactive({ value: 0 as -1 | 0 | 1, comment: '' })
const note = ref('')

function apply(x: AdminApplicationDetail) {
  app.value = x
  decision.status = x.status === 'withdrawn' ? 'review' : x.status
  decision.response = x.response ?? ''
  const mine = x.voteList.find((v) => v.uuid === session.value?.uuid)
  vote.value = x.votes.mine ?? 0
  vote.comment = mine?.comment ?? ''
}
async function load() {
  error.value = ''
  try {
    apply((await api<{ application: AdminApplicationDetail }>(`/v1/admin/applications/${id.value}`)).application)
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}
onMounted(load)

async function run(fn: () => Promise<{ application: AdminApplicationDetail }>) {
  busy.value = true
  actionError.value = ''
  try {
    apply((await fn()).application)
    return true
  } catch (e) {
    actionError.value = fill(a.value.common.failed, { error: apiMessage(e) })
    return false
  } finally {
    busy.value = false
  }
}
const decisionOptions = computed(() => {
  const x = app.value
  if (!x) return []
  const review: ApplicationStatus[] = ['new', 'review', 'interview']
  const decide: ApplicationStatus[] = ['accepted', 'rejected']
  return [...(x.can.review || x.can.decide ? review : []), ...(x.can.decide ? decide : [])]
})
function saveStatus() {
  const x = app.value
  if (!x) return
  void run(() => api(`/v1/admin/applications/${x.id}/status`, {
    method: 'POST',
    body: {
      status: decision.status,
      response: decision.response.trim(),
      ...(decision.status === 'accepted' && x.can.grantRole && decision.grantRole && !x.roleGranted ? { grantRole: true } : {}),
    },
  }))
}
function saveVote() {
  if (!app.value || vote.value === 0) return
  void run(() => api(`/v1/admin/applications/${app.value!.id}/vote`, { method: 'PUT', body: { vote: vote.value, ...(vote.comment.trim() ? { comment: vote.comment.trim() } : {}) } }))
}
function removeVote() {
  if (!app.value) return
  void run(() => api(`/v1/admin/applications/${app.value!.id}/vote`, { method: 'DELETE' }))
}
async function addNote() {
  if (!app.value || !note.value.trim()) return
  if (await run(() => api(`/v1/admin/applications/${app.value!.id}/notes`, { method: 'POST', body: { text: note.value.trim() } }))) note.value = ''
}

function answerText(f: FormField, v: AnswerValue | undefined): string {
  if (v === undefined || v === null || v === '') return t.value.adm.apps.noAnswer
  if (f.type === 'yesno') return v ? t.value.job.yes : t.value.job.no
  if (f.type === 'single') return inLang(f.options?.find((o) => o.id === v)?.label, lang.value) ?? String(v)
  if (f.type === 'multi' && Array.isArray(v)) return v.map((id) => inLang(f.options?.find((o) => o.id === id)?.label, lang.value) ?? id).join(', ')
  return String(v)
}
const roleLabel = computed(() => {
  const r = app.value?.jobRole
  return r ? (r.name ?? t.value.adm.roleNames[r.id] ?? r.id) : ''
})
const histLabel = (h: AdminApplicationDetail['history'][number]) => {
  const base = t.value.adm.apps.historyActions[h.action] ?? h.action
  if (h.action === 'vote') return `${base}: ${h.detail === 'up' ? t.value.adm.apps.up : h.detail === 'down' ? t.value.adm.apps.down : '–'}`
  if (h.action === 'status' && h.detail) {
    const [from, to] = h.detail.split(' → ')
    return `${base}: ${t.value.myApps.status[from ?? ''] ?? from} → ${t.value.myApps.status[to ?? ''] ?? to}`
  }
  if (h.action === 'role' && h.detail) return `${base}: ${t.value.adm.roleNames[h.detail] ?? h.detail}`
  return base
}
</script>

<template>
  <div class="adm-page">
    <NuxtLink to="/admin/applications" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ t.adm.apps.title }}</NuxtLink>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="!app && !error" class="skeleton mt-4 h-64 rounded-xl" />

    <template v-if="app">
      <header class="mt-4 flex flex-wrap items-center gap-4">
        <PlayerHead :uuid="app.applicant.uuid" :name="app.applicant.name" :size="56" />
        <div class="min-w-0 flex-1">
          <h1 class="adm-title flex flex-wrap items-center gap-3">{{ app.applicant.name }}
            <span class="tone text-sm" :class="applicationTone(app.status)">{{ t.myApps.status[app.status] }}</span>
          </h1>
          <p class="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-base-300">
            <span class="chip py-0.5">{{ inLang(app.job.title, lang) ?? app.job.id }}</span>
            <span><SiteIcon name="discord" class="mr-1 inline size-4 align-[-3px] text-base-400" />{{ app.discord }}</span>
            <span>{{ t.adm.apps.age }}: {{ t.job.ages[app.ageGroup] }}</span>
            <span :title="when(app.createdAt)">{{ t.adm.apps.submitted }} {{ rel(app.createdAt) }}</span>
            <span class="font-mono text-xs uppercase text-base-400">{{ app.lang }}</span>
          </p>
        </div>
        <NuxtLink v-if="can('players.view')" :to="`/admin/players/${app.applicant.uuid}`" class="btn btn-ghost"><SiteIcon name="user" class="size-4" />{{ t.adm.apps.openFile }}</NuxtLink>
      </header>
      <p v-if="actionError" role="alert" class="mt-4 text-sm text-redstone-300">{{ actionError }}</p>

      <div class="mt-6 grid gap-6 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <section class="min-w-0 space-y-6">
          <div class="card p-5">
            <h2 class="section-title">{{ t.adm.apps.answers }}</h2>
            <p v-if="!app.form.length" class="mt-3 text-sm text-base-400">{{ t.adm.apps.noAnswer }}</p>
            <dl v-else class="mt-3 space-y-4">
              <div v-for="f in app.form" :key="f.id">
                <dt class="text-xs font-semibold tracking-wide text-base-400 uppercase">{{ inLang(f.label, lang) ?? f.id }}</dt>
                <dd class="adm-note mt-1 text-sm whitespace-pre-line text-base-100">{{ answerText(f, app.answers[f.id]) }}</dd>
              </div>
            </dl>
          </div>

          <div class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="note" class="size-4 text-base-400" />{{ t.adm.apps.notes }}</h2>
            <form v-if="app.can.review" class="mt-3" @submit.prevent="addNote">
              <textarea v-model="note" class="field min-h-20" maxlength="2000" :placeholder="t.adm.apps.notePlaceholder" :aria-label="t.adm.apps.notes" />
              <button type="submit" class="btn btn-ghost mt-2" :disabled="busy || !note.trim()">{{ t.adm.apps.addNote }}</button>
            </form>
            <p v-if="!app.noteList.length" class="mt-3 text-xs text-base-400">{{ t.adm.apps.noNotes }}</p>
            <ul v-else class="mt-3 space-y-2">
              <li v-for="n in app.noteList" :key="n.id" class="rounded-md bg-base-950 px-3 py-2 text-sm">
                <p class="text-xs text-base-400">{{ actor(n.actor) }} · {{ when(n.at) }}</p>
                <p class="adm-note mt-1 whitespace-pre-line text-base-100">{{ n.text }}</p>
              </li>
            </ul>
          </div>

          <div class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="list" class="size-4 text-base-400" />{{ t.adm.apps.history }}</h2>
            <ol class="mt-3 space-y-1.5 border-l border-base-800 pl-3 text-sm">
              <li v-for="(h, i) in app.history" :key="i" class="text-base-200">
                <span class="text-xs text-base-400">{{ when(h.at) }}</span> · {{ histLabel(h) }} · <span class="text-base-400">{{ actor(h.actor) }}</span>
              </li>
            </ol>
          </div>
        </section>

        <aside class="space-y-4">
          <!-- Entscheidung -->
          <div class="card p-5">
            <h2 class="section-title">{{ t.adm.apps.decision }}</h2>
            <p v-if="app.status === 'withdrawn'" class="mt-3 text-sm text-base-400">{{ t.adm.apps.closed }}</p>
            <template v-else>
              <div class="mt-3 grid grid-cols-2 gap-1.5">
                <button v-for="s in decisionOptions" :key="s" type="button" class="adm-option text-sm" :aria-pressed="decision.status === s" @click="decision.status = s">
                  <span class="tone" :class="applicationTone(s)">{{ t.myApps.status[s] }}</span>
                </button>
              </div>
              <p v-if="!app.can.decide" class="mt-2 text-xs text-base-400">{{ t.adm.apps.reviewOnly }}</p>
              <label class="label mt-4" for="app-response">{{ t.adm.apps.response }}</label>
              <textarea id="app-response" v-model="decision.response" class="field min-h-24" maxlength="2000" />
              <p class="mt-1 text-xs text-base-400">{{ t.adm.apps.responseHint }}</p>
              <label v-if="decision.status === 'accepted' && app.jobRole && app.can.grantRole && !app.roleGranted" class="mt-3 flex items-center gap-2 text-sm text-base-200">
                <input v-model="decision.grantRole" type="checkbox" class="adm-check mt-0" />{{ fill(t.adm.apps.grantRole, { role: roleLabel }) }}
              </label>
              <p v-if="app.roleGranted" class="mt-3 flex items-center gap-2 text-sm text-ok"><SiteIcon name="check" class="size-4" />{{ t.adm.apps.historyActions.role }}: {{ roleLabel }}</p>
              <button type="button" class="btn btn-primary mt-4 w-full" :disabled="busy || !decisionOptions.length" @click="saveStatus">{{ t.adm.apps.saveStatus }}</button>
              <p v-if="app.decidedBy" class="mt-2 text-xs text-base-400">{{ actor(app.decidedBy) }} · {{ when(app.decidedAt) }}</p>
            </template>
          </div>

          <!-- Abstimmung -->
          <div class="card p-5">
            <div class="flex items-center gap-3">
              <h2 class="section-title flex-1">{{ t.adm.apps.votes }}</h2>
              <span class="flex items-center gap-1 text-ok tabular-nums"><SiteIcon name="thumbUp" class="size-4" />{{ app.votes.up }}</span>
              <span class="flex items-center gap-1 text-redstone-300 tabular-nums"><SiteIcon name="thumbDown" class="size-4" />{{ app.votes.down }}</span>
            </div>
            <div class="vote-bar mt-3" :style="{ '--up': app.votes.up + app.votes.down ? app.votes.up / (app.votes.up + app.votes.down) : 0.5 }" aria-hidden="true" />
            <p v-if="!app.voteList.length" class="mt-3 text-xs text-base-400">{{ t.adm.apps.noVotes }}</p>
            <ul v-else class="mt-3 space-y-2">
              <li v-for="v in app.voteList" :key="v.uuid" class="flex gap-2 text-sm">
                <SiteIcon :name="v.vote === 1 ? 'thumbUp' : 'thumbDown'" class="mt-0.5 size-4 shrink-0" :class="v.vote === 1 ? 'text-ok' : 'text-redstone-300'" />
                <div class="min-w-0">
                  <p class="text-base-100">{{ v.name ?? v.uuid.slice(0, 8) }} <span class="text-xs text-base-400">· {{ rel(v.at) }}</span></p>
                  <p v-if="v.comment" class="adm-note text-xs text-base-300">{{ v.comment }}</p>
                </div>
              </li>
            </ul>
            <form v-if="app.can.vote" class="mt-4 border-t border-base-800 pt-4" @submit.prevent="saveVote">
              <p class="label">{{ t.adm.apps.yourVote }}</p>
              <div class="grid grid-cols-2 gap-1.5">
                <button type="button" class="adm-option flex items-center justify-center gap-1.5 text-sm" :aria-pressed="vote.value === 1" @click="vote.value = 1"><SiteIcon name="thumbUp" class="size-4" />{{ t.adm.apps.up }}</button>
                <button type="button" class="adm-option flex items-center justify-center gap-1.5 text-sm" :aria-pressed="vote.value === -1" @click="vote.value = -1"><SiteIcon name="thumbDown" class="size-4" />{{ t.adm.apps.down }}</button>
              </div>
              <input v-model="vote.comment" class="field mt-2" maxlength="500" :placeholder="t.adm.apps.voteComment" :aria-label="t.adm.apps.voteComment" />
              <div class="mt-2 flex gap-2">
                <button type="submit" class="btn btn-primary flex-1" :disabled="busy || vote.value === 0">{{ t.adm.apps.saveVote }}</button>
                <button v-if="app.votes.mine !== null" type="button" class="btn btn-ghost" :disabled="busy" @click="removeVote">{{ t.adm.apps.removeVote }}</button>
              </div>
            </form>
          </div>

          <!-- Strafverlauf (players.view) -->
          <div v-if="app.player" class="card p-5">
            <h2 class="section-title flex items-center gap-2"><SiteIcon name="gavel" class="size-4 text-base-400" />{{ t.adm.apps.sanctions }}</h2>
            <p v-if="app.player.rank" class="mt-2 text-xs text-lamp-300">{{ fill(t.adm.apps.inTeam, { rank: app.player.rank }) }}</p>
            <p v-if="!app.player.sanctions.length" class="mt-3 text-sm text-ok">{{ t.adm.apps.noSanctions }}</p>
            <template v-else>
              <p v-if="app.player.active" class="mt-2"><span class="tone tone-danger">{{ fill(t.adm.apps.activeSanctions, { n: app.player.active }) }}</span></p>
              <ul class="mt-3 space-y-1.5 text-sm">
                <li v-for="s in app.player.sanctions.slice(0, 8)" :key="s.id" class="flex flex-wrap items-center gap-2" :class="{ 'opacity-60': s.status !== 'active' }">
                  <span class="tone" :class="kindTone(s.kind)">{{ a.kinds[s.kind] }}</span>
                  <span class="text-base-200">{{ a.reasons[s.reasonCode] ?? s.reasonCode }}</span>
                  <span class="ml-auto text-xs text-base-400">{{ rel(s.createdAt) }}</span>
                </li>
              </ul>
            </template>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.vote-bar {
  height: 6px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--color-ok) calc(var(--up) * 100%), var(--color-redstone-500) calc(var(--up) * 100%));
  opacity: 0.8;
}
</style>
