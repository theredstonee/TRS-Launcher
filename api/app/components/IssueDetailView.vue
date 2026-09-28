<script setup lang="ts">
// Ein Issue (§28) – als ganze Seite (/issues/<nr>) und als Fenster über Board/Liste: Beschreibung (Markdown, sicher
// gerendert), Screenshots, technische Infos aus dem TRS Client, Kommentare + Verlauf als eine Zeitleiste,
// Seitenleiste mit Score, Status, Zuständig, Priorität, Bereich, Tags und „Erledigt in“. Team-Mitglieder sehen
// zusätzlich die Verwaltung. `initial` = serverseitig geladene Daten (ohne persönliche Daten); im Browser wird mit
// Sitzung neu geladen (eigene Stimme, Folgen, Rechte, Log). Änderungen gehen per `changed` an Board/Liste.
import {
  ISSUE_AREAS,
  ISSUE_LIMITS,
  ISSUE_PRIORITIES,
  ISSUE_STATUSES,
  ISSUE_TYPES,
  ISSUE_VERSION,
  isClosed,
  type IssueCommentView,
  type IssueDetail,
  type IssueHistoryEntry,
  type IssueNoteView,
  type IssuePageView,
  type PlayerRefView,
  type UploadView,
} from '#shared/issues'
import { renderUserMarkdown } from '~/utils/markdown'

type IssuePageData = IssuePageView

const props = withDefaults(defineProps<{ nr: number, initial?: IssuePageData | null, modal?: boolean }>(), { initial: null, modal: false })
const emit = defineEmits<{ changed: [issue: IssueDetail], loaded: [issue: IssueDetail | null] }>()
const nr = computed(() => props.nr)
const { it, fill, date, dateTime, errorText } = useIssueText()
const { m } = useLang()
const lp = useLocalePath()
const { account, load, api, loginUrl } = useAccount()

const data = ref<IssuePageData | null>(props.initial)
const loading = ref(false)
const missing = ref(!props.initial && !props.modal)
async function reload() {
  if (!nr.value) {
    missing.value = true
    return
  }
  loading.value = true
  try {
    data.value = await $fetch<IssuePageData>(`/v1/issues/${nr.value}`, { credentials: 'same-origin' })
    missing.value = false
  } catch {
    if (!data.value) missing.value = true
  } finally {
    loading.value = false
  }
  emit('loaded', data.value?.issue ?? null)
}
const issue = computed(() => data.value?.issue ?? null)
onMounted(async () => {
  const signedIn = await load()
  if (!data.value || signedIn) await reload()
})
watch(nr, () => {
  data.value = null
  void reload()
})
// Board/Liste aktuell halten (Stimmen, Status, Folgen …).
watch(issue, (v) => {
  if (v) emit('changed', v)
}, { deep: true })

const signedIn = computed(() => !!account.value)
const closed = computed(() => !!issue.value && isClosed(issue.value.status))
const description = computed(() => (issue.value?.description ? renderUserMarkdown(issue.value.description) : ''))
const md = (s: string | null) => (s ? renderUserMarkdown(s) : '')

// --- Zeitleiste: Kommentare + Verlauf nach Zeit ---------------------------------------------------------------
type Item = { kind: 'comment', at: string, c: IssueCommentView } | { kind: 'event', at: string, e: IssueHistoryEntry }
const timeline = computed<Item[]>(() => {
  const d = data.value
  if (!d) return []
  return [
    ...d.comments.map((c) => ({ kind: 'comment' as const, at: c.createdAt, c })),
    ...d.history.filter((e) => e.action !== 'deleted' && e.action !== 'restored').map((e) => ({ kind: 'event' as const, at: e.at, e })),
  ].sort((a, b) => a.at.localeCompare(b.at))
})
function valueLabel(action: string, v: string | null): string {
  if (v === null) return ''
  if (action === 'status') return it.value.statuses[v] ?? v
  if (action === 'priority') return it.value.priorities[v] ?? v
  if (action === 'type') return it.value.types[v] ?? v
  if (action === 'area') return it.value.areas[v] ?? v
  return v
}
function eventText(e: IssueHistoryEntry): string {
  const ev = it.value.detail.events
  let key: string = e.action
  if (e.action === 'assignee' && e.to === null) key = 'unassigned'
  if (e.action === 'fixed_in' && e.to === null) key = 'fixed_in_removed'
  if (e.action === 'tags' && e.to === null) key = 'tags_removed'
  return fill(ev[key] ?? key, { from: valueLabel(e.action, e.from), to: valueLabel(e.action, e.to) })
}
const actorName = (p: PlayerRefView | null) => p?.name || it.value.detail.teamName
/** Symbol + Farbe je Art der Änderung (Status = Pfeile, Priorität = Fahne …). */
function eventLook(e: IssueHistoryEntry): { icon: string, tone: string } {
  switch (e.action) {
    case 'status': return { icon: 'swap', tone: e.to === 'done' ? 'ok' : e.to === 'rejected' ? 'red' : 'blue' }
    case 'priority': return { icon: 'flag', tone: 'amber' }
    case 'assignee': return { icon: 'user', tone: 'violet' }
    case 'tags': return { icon: 'tag', tone: 'grey' }
    case 'fixed_in': return { icon: 'check', tone: e.to === null ? 'grey' : 'ok' }
    case 'type': return { icon: e.to === 'feature' ? 'bolt' : 'bug', tone: 'red' }
    case 'area': return { icon: 'blocks', tone: 'grey' }
    case 'title': return { icon: 'pencil', tone: 'grey' }
    case 'locked': return { icon: 'lock', tone: 'red' }
    case 'unlocked': return { icon: 'unlock', tone: 'ok' }
    case 'merged_into':
    case 'merged_from': return { icon: 'merge', tone: 'violet' }
    default: return { icon: 'refresh', tone: 'grey' }
  }
}
/** „Erstellt von {name}“ → Text vor/nach dem Namen (der Name wird fett gesetzt). */
const openedByParts = computed<[string, string]>(() => {
  const [a = '', b = ''] = it.value.detail.openedBy.split('{name}')
  return [a, b]
})

// --- Folgen, Link kopieren --------------------------------------------------------------------------------------
const followBusy = ref(false)
const actionError = ref('')
async function toggleFollow() {
  const i = issue.value
  if (!i) return
  if (!signedIn.value) {
    window.location.href = loginUrl()
    return
  }
  followBusy.value = true
  actionError.value = ''
  try {
    await api(`/v1/issues/${i.number}/follow`, { method: i.following ? 'DELETE' : 'PUT' })
    i.following = !i.following
  } catch (e) {
    actionError.value = errorText(e)
  } finally {
    followBusy.value = false
  }
}
const copied = ref(false)
async function copyLink() {
  if (!issue.value) return
  try {
    await navigator.clipboard.writeText(issue.value.url)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    // Zwischenablage gesperrt – der Link steht ohnehin in der Adresszeile.
  }
}
function onVoted(r: { score: number, up: number, down: number, myVote: -1 | 0 | 1 }) {
  if (issue.value) Object.assign(issue.value, r)
}

// --- Eigenes Issue bearbeiten ---------------------------------------------------------------------------------
const editing = ref(false)
const editTitle = ref('')
const editBody = ref('')
const editType = ref<'bug' | 'feature'>('bug')
const editBusy = ref(false)
const editError = ref('')
function startEdit() {
  const i = issue.value
  if (!i) return
  editTitle.value = i.title
  editBody.value = i.description
  editType.value = i.type
  editError.value = ''
  editing.value = true
}
async function saveEdit() {
  const i = issue.value
  if (!i || editBusy.value) return
  editBusy.value = true
  editError.value = ''
  try {
    const r = await api<{ issue: IssueDetail }>(`/v1/issues/${i.number}`, { method: 'PATCH', body: { title: editTitle.value, description: editBody.value, type: editType.value } })
    data.value = { ...data.value!, issue: r.issue }
    editing.value = false
  } catch (e) {
    editError.value = errorText(e)
  } finally {
    editBusy.value = false
  }
}

// --- Kommentare -----------------------------------------------------------------------------------------------
const commentText = ref('')
const commentImages = ref<UploadView[]>([])
const commentBusy = ref(false)
const commentError = ref('')
async function sendComment() {
  const i = issue.value
  if (!i || commentBusy.value || !commentText.value.trim()) return
  commentBusy.value = true
  commentError.value = ''
  try {
    const r = await api<{ comment: IssueCommentView }>(`/v1/issues/${i.number}/comments`, {
      method: 'POST',
      body: { body: commentText.value, attachments: commentImages.value.map((x) => x.id) },
    })
    data.value!.comments.push(r.comment)
    i.comments++
    i.following = true
    commentText.value = ''
    commentImages.value = []
  } catch (e) {
    commentError.value = errorText(e)
  } finally {
    commentBusy.value = false
  }
}
const editingComment = ref<number | null>(null)
const editCommentText = ref('')
async function saveComment(c: IssueCommentView) {
  try {
    const r = await api<{ comment: IssueCommentView }>(`/v1/issues/${nr.value}/comments/${c.id}`, { method: 'PATCH', body: { body: editCommentText.value } })
    Object.assign(c, r.comment)
    editingComment.value = null
  } catch (e) {
    actionError.value = errorText(e)
  }
}
async function deleteComment(c: IssueCommentView, asTeam: boolean) {
  if (!window.confirm(asTeam ? it.value.team.removeConfirm : it.value.detail.confirmDelete)) return
  try {
    await api(asTeam ? `/v1/admin/issues/${nr.value}/comments/${c.id}` : `/v1/issues/${nr.value}/comments/${c.id}`, { method: 'DELETE' })
    Object.assign(c, { deleted: true, deletedBy: asTeam ? 'team' : 'author', body: null, attachments: [] })
    if (issue.value) issue.value.comments = Math.max(0, issue.value.comments - 1)
  } catch (e) {
    actionError.value = errorText(e)
  }
}

// --- Melden ---------------------------------------------------------------------------------------------------
const REASONS = ['spam', 'insult_hate', 'inappropriate', 'harassment', 'scam_phishing', 'other'] as const
const reportTarget = ref<{ kind: 'issue' } | { kind: 'issue_comment', id: number } | null>(null)
const reportReason = ref<(typeof REASONS)[number]>('spam')
const reportMsg = ref('')
const reportErr = ref('')
const reportBusy = ref(false)
function openReport(t: { kind: 'issue' } | { kind: 'issue_comment', id: number }) {
  if (!signedIn.value) {
    window.location.href = loginUrl()
    return
  }
  reportTarget.value = t
  reportErr.value = ''
  reportMsg.value = ''
}
async function sendReport() {
  const t = reportTarget.value
  if (!t || reportBusy.value) return
  reportBusy.value = true
  reportErr.value = ''
  try {
    const body = t.kind === 'issue' ? { kind: 'issue', issueNumber: nr.value, reason: reportReason.value } : { kind: 'issue_comment', commentId: t.id, reason: reportReason.value }
    await api('/v1/reports', { method: 'POST', body })
    reportMsg.value = it.value.detail.reportDone
    reportTarget.value = null
  } catch (e) {
    reportErr.value = apiCode(e) === 'already_reported' ? it.value.detail.reportAlready : errorText(e)
  } finally {
    reportBusy.value = false
  }
}

// --- Team -----------------------------------------------------------------------------------------------------
const canManage = computed(() => !!issue.value?.can.manage)
const canModerate = computed(() => !!issue.value?.can.moderate)
const staffList = ref<PlayerRefView[]>([])
const tf = reactive({ status: '', priority: '', assignee: '', tags: '', fixedIn: '', type: '', area: '' })
const teamMsg = ref('')
const teamErr = ref('')
const teamBusy = ref(false)
function fillTeamForm() {
  const i = issue.value
  if (!i) return
  tf.status = i.status
  tf.priority = i.priority ?? ''
  tf.assignee = i.assignee?.uuid ?? ''
  tf.tags = i.tags.join(', ')
  tf.fixedIn = i.fixedIn ?? ''
  tf.type = i.type
  tf.area = i.area
}
watch(() => [issue.value?.number, canManage.value], async () => {
  fillTeamForm()
  if (canManage.value && !staffList.value.length) {
    try {
      staffList.value = (await api<{ staff: PlayerRefView[] }>('/v1/admin/issues/staff')).staff
    } catch {
      staffList.value = []
    }
  }
}, { immediate: true })
const fixedValid = computed(() => tf.fixedIn.trim() === '' || ISSUE_VERSION.test(tf.fixedIn.trim()))
async function teamPatch(body: Record<string, unknown>) {
  const i = issue.value
  if (!i || teamBusy.value) return
  teamBusy.value = true
  teamErr.value = ''
  teamMsg.value = ''
  try {
    const r = await api<{ issue: IssueDetail, history: IssueHistoryEntry[] }>(`/v1/admin/issues/${i.number}`, { method: 'PATCH', body })
    data.value = { ...data.value!, issue: r.issue, history: r.history }
    fillTeamForm()
    teamMsg.value = it.value.team.saved
  } catch (e) {
    teamErr.value = errorText(e)
  } finally {
    teamBusy.value = false
  }
}
function saveTeam() {
  const i = issue.value
  if (!i || !fixedValid.value) return
  const body: Record<string, unknown> = {}
  if (tf.status !== i.status) body.status = tf.status
  if ((tf.priority || null) !== i.priority) body.priority = tf.priority || null
  if ((tf.assignee || null) !== (i.assignee?.uuid ?? null)) body.assignee = tf.assignee || null
  const tags = tf.tags.split(',').map((x) => x.trim().toLowerCase()).filter(Boolean)
  if ([...tags].sort().join(',') !== [...i.tags].sort().join(',')) body.tags = tags
  if ((tf.fixedIn.trim() || null) !== i.fixedIn) body.fixedIn = tf.fixedIn.trim() || null
  if (tf.type !== i.type) body.type = tf.type
  if (tf.area !== i.area) body.area = tf.area
  if (Object.keys(body).length) void teamPatch(body)
}
const mergeInto = ref('')
async function merge() {
  const i = issue.value
  const into = Number(mergeInto.value.replace('#', ''))
  if (!i || !Number.isSafeInteger(into) || into < 1) return
  if (!window.confirm(fill(it.value.team.mergeConfirm, { from: i.number, to: into }))) return
  try {
    await api(`/v1/admin/issues/${i.number}/merge`, { method: 'POST', body: { into } })
    mergeInto.value = ''
    await reload()
  } catch (e) {
    teamErr.value = errorText(e)
  }
}
const noteText = ref('')
async function addNote() {
  const i = issue.value
  if (!i || !noteText.value.trim()) return
  try {
    const r = await api<{ notes: IssueNoteView[] }>(`/v1/admin/issues/${i.number}/notes`, { method: 'POST', body: { text: noteText.value } })
    i.notes = r.notes
    noteText.value = ''
  } catch (e) {
    teamErr.value = errorText(e)
  }
}
const deleteReason = ref('')
async function deleteIssue() {
  const i = issue.value
  if (!i || !window.confirm(fill(it.value.team.deleteConfirm, { n: i.number }))) return
  try {
    await api(`/v1/admin/issues/${i.number}`, { method: 'DELETE', body: deleteReason.value.trim() ? { reason: deleteReason.value.trim() } : undefined })
    await reload()
  } catch (e) {
    teamErr.value = errorText(e)
  }
}
async function restoreIssue() {
  const i = issue.value
  if (!i) return
  try {
    await api(`/v1/admin/issues/${i.number}/restore`, { method: 'POST' })
    await reload()
  } catch (e) {
    teamErr.value = errorText(e)
  }
}
</script>

<template>
  <div :class="{ 'in-modal': modal }">
    <div v-if="!issue && !missing" class="space-y-4 py-4" aria-busy="true">
      <div class="skeleton h-9 w-2/3 rounded-lg" />
      <div class="skeleton h-5 w-1/3 rounded-lg" />
      <div class="skeleton h-48 rounded-xl" />
    </div>
    <section v-else-if="!issue" class="mx-auto max-w-xl py-20 text-center">
      <SiteIcon name="bug" class="mx-auto size-10 text-base-400" />
      <h1 class="display mt-4 text-3xl text-base-50">{{ it.detail.notFound }}</h1>
      <NuxtLink :to="lp('/issues')" class="btn btn-ghost mt-6">{{ it.detail.back }}</NuxtLink>
    </section>

    <template v-else>
      <p v-if="issue.deleted" class="banner danger mt-4"><SiteIcon name="trash" class="size-4 shrink-0" />{{ it.detail.deletedBanner }}</p>
      <p v-if="issue.duplicateOf" class="banner mt-4">
        <SiteIcon name="link" class="size-4 shrink-0" />
        <span>{{ fill(it.detail.duplicateBanner, { n: issue.duplicateOf.number }) }}</span>
        <NuxtLink :to="lp(`/issues/${issue.duplicateOf.number}`)" class="ml-auto font-semibold text-base-50 underline-offset-4 hover:underline">#{{ issue.duplicateOf.number }} {{ issue.duplicateOf.title }}</NuxtLink>
      </p>

      <header :class="modal ? 'mt-1' : 'mt-2'">
        <nav v-if="!modal" class="crumbs" aria-label="Breadcrumb">
          <NuxtLink :to="lp('/')">{{ m.nav.home }}</NuxtLink>
          <SiteIcon name="chevron" class="size-3 -rotate-90" aria-hidden="true" />
          <NuxtLink :to="lp('/issues')">{{ it.nav.issues }}</NuxtLink>
          <SiteIcon name="chevron" class="size-3 -rotate-90" aria-hidden="true" />
          <span aria-current="page">{{ issue.title }}</span>
        </nav>
        <div v-if="!editing" class="flex items-start gap-4" :class="modal ? '' : 'mt-4'">
          <h1 :id="`issue-title-${issue.number}`" class="display title min-w-0 flex-1 break-words text-base-50" :class="{ small: modal }">{{ issue.title }}</h1>
          <NuxtLink v-if="!modal" :to="lp('/issues/new')" class="btn btn-ghost shrink-0 max-sm:hidden"><SiteIcon name="plus" class="size-4" />{{ it.list.newIssue }}</NuxtLink>
        </div>
        <div class="mt-3 flex flex-wrap items-center gap-x-2.5 gap-y-2 text-sm text-base-400">
          <IssueVote class="mobile-vote" layout="row" :number="issue.number" :score="issue.score" :my-vote="issue.myVote ?? 0" :up="issue.up" :down="issue.down" :closed="closed" :signed-in="signedIn" @voted="onVoted" @error="actionError = $event" />
          <span class="type-badge" :data-t="issue.type"><SiteIcon :name="issue.type === 'bug' ? 'bug' : 'bolt'" class="size-3.5" />{{ it.types[issue.type] }}</span>
          <IssueStatus :status="issue.status" />
          <span class="badge bg-base-800 text-base-200">{{ it.areas[issue.area] }}</span>
          <span v-if="issue.source === 'client'" class="badge bg-base-800 text-base-200"><SiteIcon name="client" class="size-3" />{{ it.common.fromClient }}</span>
          <span class="text-base-200 tabular-nums">#{{ issue.number }}</span>
          <span v-if="issue.author">{{ openedByParts[0] }}<strong class="font-medium text-base-100">{{ issue.author.name }}</strong>{{ openedByParts[1] }}</span>
          <span aria-hidden="true">·</span>
          <time :datetime="issue.createdAt">{{ date(issue.createdAt) }}</time>
          <span aria-hidden="true">·</span>
          <span>{{ issue.comments === 1 ? it.common.comment1 : fill(it.common.comments, { n: issue.comments }) }}</span>
        </div>
      </header>

      <div class="layout" :class="modal ? 'mt-5' : 'mt-7'">
        <main class="min-w-0">
          <!-- Beschreibung als erster Beitrag / Bearbeiten -->
          <div class="post">
            <div class="avatar">
              <PlayerHead v-if="issue.author" :uuid="issue.author.uuid" :name="issue.author.name" :skin="issue.author.skin ?? null" :fetch="false" :size="36" />
              <span v-else class="avatar-empty"><SiteIcon name="user" class="size-4" /></span>
            </div>
            <article v-if="!editing" class="post-card" :class="{ team: issue.authorTeam }">
              <header class="post-head">
                <strong class="font-semibold text-base-50">{{ issue.author?.name ?? it.common.deletedUser }}</strong>
                <span v-if="issue.authorTeam" class="badge team">{{ it.common.team }}</span>
                <span class="text-base-400">{{ it.detail.postedIssue }}</span>
                <time class="text-base-400" :datetime="issue.createdAt" :title="dateTime(issue.createdAt)">{{ date(issue.createdAt) }}</time>
                <span v-if="issue.editedAt" class="edited"><SiteIcon name="pencil" class="size-3" />{{ it.detail.edited }}</span>
                <span class="ml-auto flex gap-1">
                  <button v-if="issue.can.edit" type="button" class="mini" :title="it.detail.editIssue" :aria-label="it.detail.editIssue" @click="startEdit"><SiteIcon name="pencil" class="size-3.5" /></button>
                  <button v-if="!issue.author || issue.author.uuid !== account?.uuid" type="button" class="mini" :title="it.detail.reportIssue" :aria-label="it.detail.reportIssue" @click="openReport({ kind: 'issue' })"><SiteIcon name="flag" class="size-3.5" /></button>
                </span>
              </header>
              <div class="post-body">
                <!-- eslint-disable-next-line vue/no-v-html -- renderUserMarkdown lässt kein HTML durch (tests/usermarkdown.test.ts) -->
                <div v-if="description" class="prose-md text-[0.95rem]" v-html="description" />
                <p v-else class="text-base-400">{{ it.detail.noDescription }}</p>
                <ul v-if="issue.attachments.length" class="shots mt-4" :aria-label="it.detail.attachments">
                  <li v-for="img in issue.attachments" :key="img.id">
                    <a :href="img.url" target="_blank" rel="noopener"><img :src="img.thumbUrl" :width="img.width" :height="img.height" :alt="it.detail.attachments" loading="lazy" /></a>
                  </li>
                </ul>
              </div>
              <!-- Technische Infos (TRS Client) -->
              <details v-if="issue.meta" class="tech">
                <summary class="cursor-pointer text-sm font-medium text-base-100"><SiteIcon name="terminal" class="mr-1.5 inline size-4 align-[-3px] text-base-400" />{{ it.detail.tech }}</summary>
                <p class="mt-2 text-xs text-base-400">{{ it.detail.techHint }}</p>
                <dl class="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
                  <template v-if="issue.meta.modVersion"><dt class="text-base-400">{{ it.detail.modVersion }}</dt><dd class="text-base-100">{{ issue.meta.modVersion }}</dd></template>
                  <template v-if="issue.meta.mcVersion"><dt class="text-base-400">{{ it.detail.mcVersion }}</dt><dd class="text-base-100">{{ issue.meta.mcVersion }}</dd></template>
                  <template v-if="issue.meta.loader"><dt class="text-base-400">{{ it.detail.loader }}</dt><dd class="text-base-100">{{ issue.meta.loader }}</dd></template>
                </dl>
                <details v-if="issue.meta.mods.length" class="mt-3">
                  <summary class="cursor-pointer text-sm text-base-200">{{ fill(it.detail.mods, { n: issue.meta.mods.length }) }}</summary>
                  <ul class="mods mt-2">
                    <li v-for="mod in issue.meta.mods" :key="mod">{{ mod }}</li>
                  </ul>
                </details>
                <div v-if="issue.meta.log !== undefined" class="mt-3">
                  <p class="text-sm text-base-200">{{ it.detail.log }}</p>
                  <p class="mt-1 text-xs text-base-400">{{ it.detail.logPrivate }}</p>
                  <pre class="log mt-2"><code>{{ issue.meta.log }}</code></pre>
                </div>
                <p v-else-if="issue.meta.hasLog" class="mt-3 text-xs text-base-400"><SiteIcon name="lock" class="mr-1 inline size-3.5 align-[-2px]" />{{ it.detail.logHidden }}</p>
              </details>
            </article>
            <form v-else class="post-card space-y-3 p-4" @submit.prevent="saveEdit">
              <p class="text-sm text-base-400">{{ it.detail.editHint }}</p>
              <div class="flex flex-wrap gap-2">
                <select v-model="editType" class="field w-auto" :aria-label="it.new.type">
                  <option v-for="t in ISSUE_TYPES" :key="t" :value="t">{{ it.types[t] }}</option>
                </select>
                <input v-model="editTitle" class="field min-w-0 flex-1" :maxlength="ISSUE_LIMITS.titleMax" :aria-label="it.new.titleLabel" />
              </div>
              <IssueEditor id="edit-body" v-model="editBody" :max="ISSUE_LIMITS.descriptionMax" :max-images="0" :rows="10" :label="it.new.description" />
              <p v-if="editError" role="alert" class="text-sm text-redstone-300">{{ editError }}</p>
              <div class="flex gap-2">
                <button type="submit" class="btn btn-primary" :disabled="editBusy">{{ it.common.save }}</button>
                <button type="button" class="btn btn-ghost" @click="editing = false">{{ it.common.cancel }}</button>
              </div>
            </form>
          </div>

          <!-- Aktivität: Kommentare + Verlauf nach Zeit -->
          <section class="mt-8" :aria-label="it.detail.activity">
            <h2 class="act-title">{{ it.detail.activity }}</h2>
            <p v-if="actionError" role="alert" class="mt-2 text-sm text-redstone-300">{{ actionError }}</p>
            <p v-if="reportMsg" role="status" class="mt-2 text-sm text-emerald-300">{{ reportMsg }}</p>
            <p v-if="!timeline.length" class="indent mt-3 text-sm text-base-400">{{ it.detail.noComments }}</p>
            <ol class="timeline mt-3">
              <li v-for="(item, idx) in timeline" :key="idx" :class="item.kind === 'event' ? 't-event' : 't-comment'">
                <template v-if="item.kind === 'event'">
                  <span class="event-icon" :data-k="eventLook(item.e).tone" aria-hidden="true"><SiteIcon :name="eventLook(item.e).icon" class="size-4" /></span>
                  <p class="min-w-0 text-sm text-base-400">
                    <strong class="font-semibold text-base-100">{{ actorName(item.e.actor) }}</strong> {{ eventText(item.e) }}
                    · <time :datetime="item.e.at" :title="dateTime(item.e.at)">{{ date(item.e.at) }}</time>
                  </p>
                </template>
                <div v-else :id="`c${item.c.id}`" class="post">
                  <div class="avatar">
                    <PlayerHead v-if="item.c.author" :uuid="item.c.author.uuid" :name="item.c.author.name" :skin="item.c.author.skin ?? null" :fetch="false" :size="36" />
                    <span v-else class="avatar-empty"><SiteIcon name="user" class="size-4" /></span>
                  </div>
                  <article class="post-card" :class="{ team: item.c.team, deleted: item.c.deleted }">
                    <header class="post-head">
                      <strong class="font-semibold text-base-50">{{ item.c.author?.name ?? it.common.deletedUser }}</strong>
                      <span v-if="item.c.team" class="badge team">{{ it.common.team }}</span>
                      <span class="text-base-400">{{ it.detail.commented }}</span>
                      <a :href="`#c${item.c.id}`" class="text-base-400 hover:text-base-200"><time :datetime="item.c.createdAt" :title="dateTime(item.c.createdAt)">{{ date(item.c.createdAt) }}</time></a>
                      <span v-if="item.c.editedAt && !item.c.deleted" class="edited"><SiteIcon name="pencil" class="size-3" />{{ it.detail.edited }}</span>
                      <span class="ml-auto flex gap-1">
                        <template v-if="!item.c.deleted">
                          <button v-if="item.c.mine" type="button" class="mini" :title="it.common.edit" :aria-label="it.common.edit" @click="editingComment = item.c.id; editCommentText = item.c.body ?? ''"><SiteIcon name="pencil" class="size-3.5" /></button>
                          <button v-if="item.c.mine" type="button" class="mini" :title="it.common.delete" :aria-label="it.common.delete" @click="deleteComment(item.c, false)"><SiteIcon name="trash" class="size-3.5" /></button>
                          <button v-else-if="canModerate" type="button" class="mini" :title="it.team.removeComment" :aria-label="it.team.removeComment" @click="deleteComment(item.c, true)"><SiteIcon name="trash" class="size-3.5" /></button>
                          <button v-if="!item.c.mine && item.c.author" type="button" class="mini" :title="it.detail.reportComment" :aria-label="it.detail.reportComment" @click="openReport({ kind: 'issue_comment', id: item.c.id })"><SiteIcon name="flag" class="size-3.5" /></button>
                        </template>
                      </span>
                    </header>
                    <div class="post-body">
                      <p v-if="item.c.deleted" class="text-sm text-base-400 italic">{{ item.c.deletedBy === 'team' ? it.detail.deletedTeam : it.detail.deletedAuthor }}</p>
                      <form v-else-if="editingComment === item.c.id" class="space-y-2" @submit.prevent="saveComment(item.c)">
                        <IssueEditor :id="`edit-c${item.c.id}`" v-model="editCommentText" :max="ISSUE_LIMITS.commentMax" :max-images="0" :rows="4" />
                        <div class="flex gap-2">
                          <button type="submit" class="btn btn-primary text-xs">{{ it.common.save }}</button>
                          <button type="button" class="btn btn-ghost text-xs" @click="editingComment = null">{{ it.common.cancel }}</button>
                        </div>
                      </form>
                      <template v-else>
                        <!-- eslint-disable-next-line vue/no-v-html -- renderUserMarkdown lässt kein HTML durch -->
                        <div class="prose-md text-sm" v-html="md(item.c.body)" />
                        <ul v-if="item.c.attachments.length" class="shots mt-3">
                          <li v-for="img in item.c.attachments" :key="img.id">
                            <a :href="img.url" target="_blank" rel="noopener"><img :src="img.thumbUrl" :width="img.width" :height="img.height" :alt="it.detail.attachments" loading="lazy" /></a>
                          </li>
                        </ul>
                      </template>
                    </div>
                  </article>
                </div>
              </li>
            </ol>

            <!-- Melden -->
            <form v-if="reportTarget" class="card indent-m mt-4 max-w-xl p-5" @submit.prevent="sendReport">
              <h3 class="text-base font-semibold text-base-50">{{ it.detail.reportTitle }}</h3>
              <p class="mt-1 text-sm text-base-300">{{ it.detail.reportLead }}</p>
              <label class="mt-4 block text-xs text-base-400" for="issue-report-reason">{{ it.detail.reportReason }}</label>
              <select id="issue-report-reason" v-model="reportReason" class="field mt-1 w-full">
                <option v-for="r in REASONS" :key="r" :value="r">{{ m.share.reasons[r] }}</option>
              </select>
              <p v-if="reportErr" role="alert" class="mt-3 text-sm text-redstone-300">{{ reportErr }}</p>
              <div class="mt-4 flex gap-2">
                <button type="submit" class="btn btn-danger text-sm" :disabled="reportBusy">{{ it.detail.reportSend }}</button>
                <button type="button" class="btn btn-ghost text-sm" @click="reportTarget = null">{{ it.common.cancel }}</button>
              </div>
            </form>

            <!-- Kommentar schreiben -->
            <div class="mt-8">
              <p v-if="issue.locked && !issue.can.comment" class="banner indent-m"><SiteIcon name="lock" class="size-4 shrink-0" />{{ it.detail.lockedText }}</p>
              <p v-else-if="!signedIn" class="indent text-sm text-base-400 italic">
                <a :href="loginUrl()" class="text-base-200 underline-offset-4 hover:text-base-50 hover:underline">{{ it.detail.signInToComment }}</a>
              </p>
              <div v-else-if="issue.can.comment" class="post">
                <div class="avatar">
                  <PlayerHead v-if="account" :uuid="account.uuid" :name="account.name" :skin="account.skin ?? null" :fetch="false" :size="36" />
                </div>
                <form class="min-w-0 space-y-2" @submit.prevent="sendComment">
                  <label for="new-comment" class="sr-only">{{ it.detail.write }}</label>
                  <IssueEditor id="new-comment" v-model="commentText" v-model:images="commentImages" :placeholder="it.detail.placeholder" :max="ISSUE_LIMITS.commentMax" :max-images="ISSUE_LIMITS.uploadsPerComment" :rows="4" />
                  <p v-if="commentError" role="alert" class="text-sm text-redstone-300">{{ commentError }}</p>
                  <div class="flex justify-end">
                    <button type="submit" class="btn btn-primary" :disabled="commentBusy || !commentText.trim()" data-testid="send-comment">
                      <SiteIcon name="chat" class="size-4" />{{ it.detail.send }}
                    </button>
                  </div>
                </form>
              </div>
            </div>
          </section>
        </main>

        <aside class="space-y-5">
          <section class="card score-card" :aria-label="it.detail.sidebar.score">
            <p class="text-sm font-medium text-base-200">{{ it.detail.sidebar.score }}</p>
            <IssueVote size="lg" layout="row" class="mt-2" :number="issue.number" :score="issue.score" :my-vote="issue.myVote ?? 0" :up="issue.up" :down="issue.down" :closed="closed" :signed-in="signedIn" @voted="onVoted" @error="actionError = $event" />
            <p class="mt-1.5 text-xs text-base-400 tabular-nums">{{ fill(it.detail.sidebar.votes, { up: issue.up, down: issue.down }) }}</p>
          </section>

          <div>
            <div class="flex gap-2">
              <button type="button" class="btn flex-1" :class="issue.following ? 'btn-ghost following' : 'btn-primary'" :disabled="followBusy" :aria-pressed="!!issue.following" data-testid="follow" @click="toggleFollow">
                <SiteIcon :name="issue.following ? 'check' : 'bell'" class="size-4" />{{ issue.following ? it.detail.following : it.detail.follow }}
              </button>
              <button type="button" class="btn-icon" :title="copied ? it.common.copied : it.common.copyLink" :aria-label="it.common.copyLink" @click="copyLink">
                <SiteIcon :name="copied ? 'check' : 'link'" class="size-4" />
              </button>
            </div>
            <p class="mt-2 text-xs leading-relaxed text-base-400">{{ it.detail.followHint }}</p>
          </div>

          <div class="facts">
            <section>
              <h3>{{ it.detail.sidebar.assignee }}</h3>
              <p v-if="issue.assignee" class="inline-flex items-center gap-2 text-sm text-base-100"><PlayerHead :uuid="issue.assignee.uuid" :name="issue.assignee.name" :skin="issue.assignee.skin ?? null" :fetch="false" :size="20" />{{ issue.assignee.name }}</p>
              <p v-else class="text-sm text-base-400">{{ it.detail.sidebar.nobody }}</p>
            </section>
            <section>
              <h3>{{ it.detail.sidebar.area }}</h3>
              <div class="flex flex-wrap gap-1.5">
                <span v-for="a in ISSUE_AREAS" :key="a" class="pill" :class="{ on: a === issue.area }">{{ it.areas[a] }}</span>
              </div>
            </section>
            <section>
              <h3>{{ it.detail.sidebar2.versionLoader }}</h3>
              <div v-if="issue.meta && (issue.meta.mcVersion || issue.meta.loader || issue.meta.modVersion)" class="flex flex-wrap gap-1.5">
                <span v-if="issue.meta.mcVersion" class="pill on">{{ it.detail.mcVersion }} {{ issue.meta.mcVersion }}</span>
                <span v-if="issue.meta.loader" class="pill on">{{ issue.meta.loader }}</span>
                <span v-if="issue.meta.modVersion" class="pill">{{ it.detail.modVersion }} {{ issue.meta.modVersion }}</span>
              </div>
              <p v-else class="text-sm text-base-400">{{ it.detail.sidebar2.noVersion }}</p>
            </section>
            <section>
              <h3>{{ it.detail.sidebar2.project }}</h3>
              <dl class="kv text-sm">
                <dt>{{ it.detail.sidebar.status }}</dt>
                <dd><IssueStatus :status="issue.status" /></dd>
                <dt>{{ it.detail.sidebar.priority }}</dt>
                <dd><span class="prio" :data-p="issue.priority ?? 'none'">{{ it.priorities[issue.priority ?? 'none'] }}</span></dd>
                <dt>{{ it.detail.sidebar.fixedIn }}</dt>
                <dd><span v-if="issue.fixedIn" class="badge fixed-in"><SiteIcon name="check" class="size-3" />{{ issue.fixedIn }}</span><span v-else class="text-base-400">{{ it.detail.sidebar.notYet }}</span></dd>
              </dl>
            </section>
            <section>
              <h3>{{ it.detail.sidebar.tags }}</h3>
              <div v-if="issue.tags.length" class="flex flex-wrap gap-1.5">
                <span v-for="t in issue.tags" :key="t" class="pill tag">#{{ t }}</span>
              </div>
              <p v-else class="text-sm text-base-400">{{ it.detail.sidebar.noTags }}</p>
            </section>
            <p class="dates">{{ fill(it.detail.sidebar2.dates, { opened: date(issue.createdAt), activity: date(issue.activityAt) }) }}</p>
          </div>

          <!-- Team -->
          <section v-if="canManage || canModerate" class="card team-panel p-4" data-testid="team-panel">
            <h2 class="flex items-center gap-2 text-sm font-semibold text-base-50"><SiteIcon name="shield" class="size-4 text-redstone-400" />{{ it.team.title }}</h2>
            <form v-if="canManage" class="mt-3 space-y-2.5" @submit.prevent="saveTeam">
              <label class="block"><span class="label">{{ it.team.status }}</span>
                <select v-model="tf.status" class="field">
                  <option v-for="s in ISSUE_STATUSES.filter((x) => x !== 'duplicate' || issue!.status === 'duplicate')" :key="s" :value="s">{{ it.statuses[s] }}</option>
                </select>
              </label>
              <div class="grid grid-cols-2 gap-2">
                <label class="block"><span class="label">{{ it.team.priority }}</span>
                  <select v-model="tf.priority" class="field">
                    <option value="">{{ it.priorities.none }}</option>
                    <option v-for="p in ISSUE_PRIORITIES" :key="p" :value="p">{{ it.priorities[p] }}</option>
                  </select>
                </label>
                <label class="block"><span class="label">{{ it.team.fixedIn }}</span>
                  <input v-model="tf.fixedIn" class="field" :placeholder="it.team.fixedPlaceholder" maxlength="32" :aria-invalid="!fixedValid" />
                </label>
              </div>
              <label class="block"><span class="label">{{ it.team.assignee }}</span>
                <select v-model="tf.assignee" class="field">
                  <option value="">{{ it.team.nobody }}</option>
                  <option v-for="s in staffList" :key="s.uuid" :value="s.uuid">{{ s.name }}</option>
                  <option v-if="issue.assignee && !staffList.some((s) => s.uuid === issue!.assignee!.uuid)" :value="issue.assignee.uuid">{{ issue.assignee.name }}</option>
                </select>
              </label>
              <div class="grid grid-cols-2 gap-2">
                <label class="block"><span class="label">{{ it.team.type }}</span>
                  <select v-model="tf.type" class="field"><option v-for="t in ISSUE_TYPES" :key="t" :value="t">{{ it.types[t] }}</option></select>
                </label>
                <label class="block"><span class="label">{{ it.team.area }}</span>
                  <select v-model="tf.area" class="field"><option v-for="a in ISSUE_AREAS" :key="a" :value="a">{{ it.areas[a] }}</option></select>
                </label>
              </div>
              <label class="block"><span class="label">{{ it.team.tags }}</span>
                <input v-model="tf.tags" class="field" :placeholder="it.team.tagsHint" maxlength="160" />
              </label>
              <button type="submit" class="btn btn-primary w-full" :disabled="teamBusy || !fixedValid">{{ it.team.apply }}</button>
            </form>
            <p v-if="teamMsg" role="status" class="mt-2 text-xs text-emerald-300">{{ teamMsg }}</p>
            <p v-if="teamErr" role="alert" class="mt-2 text-xs text-redstone-300">{{ teamErr }}</p>

            <div class="mt-4 flex flex-wrap gap-2 border-t border-base-800 pt-3">
              <button type="button" class="btn btn-ghost text-xs" :disabled="teamBusy || !!issue.deleted" @click="teamPatch({ locked: !issue.locked })">
                <SiteIcon name="lock" class="size-3.5" />{{ issue.locked ? it.team.unlock : it.team.lock }}
              </button>
            </div>

            <form v-if="canManage && issue.status !== 'duplicate' && !issue.deleted" class="mt-3 flex items-end gap-2" @submit.prevent="merge">
              <label class="block min-w-0 flex-1"><span class="label">{{ it.team.mergeInto }}</span>
                <input v-model="mergeInto" class="field" inputmode="numeric" placeholder="42" maxlength="10" />
              </label>
              <button type="submit" class="btn btn-ghost text-xs" :disabled="!mergeInto.trim()">{{ it.team.mergeGo }}</button>
            </form>

            <div v-if="canManage" class="mt-4 border-t border-base-800 pt-3">
              <h3 class="text-xs font-semibold text-base-50">{{ it.team.notes }}</h3>
              <p class="text-[11px] text-base-400">{{ it.team.notesHint }}</p>
              <ul class="mt-2 space-y-2">
                <li v-for="n in issue.notes ?? []" :key="n.id" class="note">
                  <p class="text-[11px] text-base-400">{{ n.author?.name || it.detail.teamName }} · {{ dateTime(n.at) }}</p>
                  <p class="text-sm whitespace-pre-wrap text-base-100">{{ n.text }}</p>
                </li>
                <li v-if="!(issue.notes ?? []).length" class="text-xs text-base-400">{{ it.team.noNotes }}</li>
              </ul>
              <form class="mt-2 space-y-2" @submit.prevent="addNote">
                <textarea v-model="noteText" class="field" rows="2" :maxlength="ISSUE_LIMITS.noteMax" :placeholder="it.team.notePlaceholder" :aria-label="it.team.notes" />
                <button type="submit" class="btn btn-ghost w-full text-xs" :disabled="!noteText.trim()">{{ it.team.addNote }}</button>
              </form>
            </div>

            <div v-if="canModerate" class="mt-4 border-t border-base-800 pt-3">
              <template v-if="!issue.deleted">
                <input v-model="deleteReason" class="field" maxlength="300" :placeholder="it.team.deleteReason" :aria-label="it.team.deleteReason" />
                <button type="button" class="btn btn-danger mt-2 w-full text-xs" @click="deleteIssue"><SiteIcon name="trash" class="size-3.5" />{{ it.team.deleteIssue }}</button>
              </template>
              <button v-else type="button" class="btn btn-ghost w-full text-xs" @click="restoreIssue"><SiteIcon name="refresh" class="size-3.5" />{{ it.team.restore }}</button>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  gap: 2rem;
}
.mobile-vote {
  gap: 0.25rem !important;
  padding: 0 0.15rem;
  border-radius: 0.4rem;
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
@media (min-width: 960px) {
  .mobile-vote {
    display: none !important;
  }
  .layout {
    grid-template-columns: minmax(0, 1fr) 18.5rem;
    align-items: start;
  }
  aside {
    position: sticky;
    top: 5rem;
  }
  .in-modal aside {
    top: 0;
  }
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  min-width: 0;
  font-size: 0.8rem;
  color: var(--color-base-400);
}
.crumbs a:hover {
  color: var(--color-base-100);
}
.crumbs [aria-current] {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-base-200);
}
.title {
  font-size: clamp(1.7rem, 1.1rem + 2.2vw, 2.6rem);
  line-height: 1.12;
  text-wrap: balance;
}
.title.small {
  font-size: clamp(1.4rem, 1rem + 1.4vw, 1.9rem);
}
.type-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.2rem 0.55rem;
  border-radius: 0.3rem;
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-redstone-300);
  background: color-mix(in srgb, var(--color-redstone-500) 14%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-redstone-500) 40%, transparent);
}
.type-badge[data-t='feature'] {
  color: var(--color-lamp-300);
  background: color-mix(in srgb, var(--color-lamp-400) 12%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-lamp-400) 38%, transparent);
}
.banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.6rem;
  padding: 0.7rem 0.9rem;
  border-radius: 0.6rem;
  background: var(--color-base-850);
  border: 1px solid var(--color-base-700);
  font-size: 0.875rem;
  color: var(--color-base-200);
}
.banner.danger {
  border-color: color-mix(in srgb, var(--color-redstone-500) 50%, transparent);
  background: color-mix(in srgb, var(--color-redstone-900) 60%, transparent);
}

/* Beiträge: Kopf links neben der Karte (wie ein Forum), Kopfzeile der Karte mit Trennlinie. */
.post {
  display: grid;
  grid-template-columns: 2.25rem minmax(0, 1fr);
  gap: 1rem;
  align-items: start;
}
.avatar {
  width: 2.25rem;
  height: 2.25rem;
  overflow: hidden;
  border-radius: 0.3rem;
  background: var(--color-base-800);
  box-shadow: 0 0 0 1px var(--color-base-700);
}
.avatar-empty {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--color-base-400);
}
.indent {
  padding-left: 3.25rem;
}
.indent-m {
  margin-left: 3.25rem;
}
.post-card {
  position: relative;
  min-width: 0;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
}
/* Kleine Spitze zum Kopf hin. */
.post-card::before {
  content: '';
  position: absolute;
  top: 0.85rem;
  left: -6px;
  width: 10px;
  height: 10px;
  transform: rotate(45deg);
  border-left: 1px solid var(--color-base-800);
  border-bottom: 1px solid var(--color-base-800);
  background: var(--color-base-850);
}
form.post-card::before {
  display: none;
}
.post-card.team {
  border-color: color-mix(in srgb, var(--color-redstone-500) 45%, var(--color-base-800));
}
.post-card.team::before {
  border-color: color-mix(in srgb, var(--color-redstone-500) 45%, var(--color-base-800));
}
.post-card.team .post-head {
  background: color-mix(in srgb, var(--color-redstone-500) 9%, var(--color-base-850));
}
.post-card.deleted {
  background: transparent;
  border-style: dashed;
}
.post-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.5rem;
  min-height: 2.6rem;
  padding: 0.35rem 0.5rem 0.35rem 1rem;
  border-bottom: 1px solid var(--color-base-800);
  border-radius: 0.6rem 0.6rem 0 0;
  background: var(--color-base-850);
  font-size: 0.85rem;
}
.post-card.deleted .post-head {
  background: transparent;
}
.post-body {
  padding: 0.9rem 1rem 1rem;
}
.edited {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.05rem 0.4rem;
  border-radius: 0.3rem;
  font-size: 0.7rem;
  color: var(--color-base-400);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
.badge.team {
  background: color-mix(in srgb, var(--color-redstone-500) 20%, transparent);
  color: var(--color-redstone-300);
}
.badge.fixed-in {
  background: color-mix(in srgb, var(--color-ok) 14%, transparent);
  color: var(--color-ok);
}
.shots {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr));
  gap: 0.5rem;
}
.shots img {
  width: 100%;
  height: 6rem;
  object-fit: cover;
  border-radius: 0.45rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
  transition: border-color 0.12s;
}
.shots a:hover img {
  border-color: var(--color-redstone-500);
}
.tech {
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--color-base-800);
}
.tech summary::marker {
  color: var(--color-base-400);
}
.mods {
  columns: 2 12rem;
  font-family: var(--font-mono);
  font-size: 0.75rem;
  color: var(--color-base-200);
}
.log {
  max-height: 22rem;
  overflow: auto;
  padding: 0.75rem;
  border-radius: 0.5rem;
  background: var(--color-base-950);
  border: 1px solid var(--color-base-800);
  font-family: var(--font-mono);
  font-size: 0.72rem;
  line-height: 1.5;
  color: var(--color-base-200);
  white-space: pre;
}

/* Aktivität */
.act-title {
  font-size: 0.8rem;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-base-200);
}
.timeline {
  position: relative;
  display: grid;
  gap: 0.9rem;
}
/* Redstone-Leitung hinter den Köpfen. */
.timeline::before {
  content: '';
  position: absolute;
  top: 0.5rem;
  bottom: 0.5rem;
  left: calc(1.125rem - 1px);
  width: 2px;
  background: linear-gradient(var(--color-base-800), var(--color-base-800)) ;
}
.timeline > li {
  position: relative;
}
.timeline > li.t-event {
  display: grid;
  grid-template-columns: 2.25rem minmax(0, 1fr);
  gap: 1rem;
  align-items: center;
  padding-block: 0.1rem;
}
.event-icon {
  --c: var(--color-base-400);
  display: grid;
  place-items: center;
  justify-self: center;
  width: 2.1rem;
  height: 2.1rem;
  border-radius: 999px;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 13%, var(--color-base-950));
  box-shadow: 0 0 0 4px var(--color-base-950), inset 0 0 0 1px color-mix(in srgb, var(--c) 38%, transparent);
}
.event-icon[data-k='blue'] { --c: #7cc4ff; }
.event-icon[data-k='amber'] { --c: var(--color-lamp-400); }
.event-icon[data-k='violet'] { --c: #c4a5ff; }
.event-icon[data-k='ok'] { --c: var(--color-ok); }
.event-icon[data-k='red'] { --c: var(--color-redstone-400); }
.event-icon[data-k='grey'] { --c: var(--color-base-400); }
.mini {
  display: grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 0.35rem;
  color: var(--color-base-400);
}
.mini:hover {
  color: var(--color-base-50);
  background: var(--color-base-800);
}

/* Seitenleiste */
.score-card {
  padding: 1rem 1rem 0.85rem;
  text-align: center;
}
.following {
  color: var(--color-ok);
}
.facts > section {
  padding-block: 0.85rem;
  border-bottom: 1px solid var(--color-base-800);
}
.facts > section:first-child {
  padding-top: 0;
}
.facts h3 {
  margin-bottom: 0.55rem;
  font-size: 0.8rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--color-base-200);
}
.pill {
  display: inline-flex;
  align-items: center;
  padding: 0.15rem 0.55rem;
  border-radius: 0.3rem;
  font-size: 0.72rem;
  color: var(--color-base-400);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
.pill.on {
  color: var(--color-base-50);
  background: var(--color-base-800);
  box-shadow: inset 0 0 0 1px var(--color-base-600);
}
.pill.tag {
  color: var(--color-base-200);
}
.kv {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.5rem 1rem;
  align-items: center;
}
.kv dt {
  font-size: 0.75rem;
  color: var(--color-base-400);
}
.kv dd {
  min-width: 0;
  color: var(--color-base-100);
}
.dates {
  padding-top: 0.75rem;
  font-size: 0.72rem;
  color: var(--color-base-400);
}
.prio[data-p='critical'] {
  color: var(--color-redstone-300);
  font-weight: 600;
}
.prio[data-p='high'] {
  color: var(--color-lamp-300);
}
.prio[data-p='none'] {
  color: var(--color-base-400);
}
.team-panel {
  border-color: color-mix(in srgb, var(--color-redstone-500) 35%, var(--color-base-800));
}
.note {
  padding: 0.45rem 0.6rem;
  border-radius: 0.4rem;
  background: var(--color-base-950);
}
</style>
