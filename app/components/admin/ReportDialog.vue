<script setup lang="ts">
import { evidenceUrl, reportReasonIds, type ReportReason } from '~/utils/chat'
import { actionBody, type AdminReportDetail, type ReportActionId } from '~/utils/moderation'
import { draftMinutes, draftProblem, emptyDraft, type SanctionDraft } from '~/utils/team'

// Prüf-Dialog einer Chat-Meldung: Kontext (Nachrichten davor/danach, die
// gemeldete hervorgehoben), Beweisbilder, Moderationsstand von Ziel und Melder,
// Notizen, Audit-Log und die Entscheidung (Nachricht löschen, Strafe mit Art,
// Dauer und Vorlage, abweisen, erledigt). Wie auf der Website.
const props = defineProps<{ reportId: string }>()
const emit = defineEmits<{ close: []; changed: []; open: [id: string] }>()

const report = ref<AdminReportDetail | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)
const keepOpen = ref(false)
const includeRelated = ref(false)
const note = ref('')
const image = ref<string | null>(null)
const team = useTeam()
/** Strafe aus der Meldung: Formular → Bestätigung → `action: sanction`. */
const sanctioning = ref<'form' | 'confirm' | null>(null)
const draft = ref<SanctionDraft>(emptyDraft('warn'))
const draftError = computed(() => draftProblem(draft.value, team.limits.value))

const resolved = computed(() => report.value?.status === 'resolved')
const focusId = computed(() => report.value?.evidence?.focus ?? null)
const reasonLabel = (r: string) => (reportReasonIds as readonly string[]).includes(r) ? t(`social.report.reasons.${r as ReportReason}`) : r
const name = (p: { name: string } | null | undefined) => p?.name || t('admin.mod.unknown')

async function load() {
  error.value = null
  try {
    report.value = (await backend.social.adminReport(props.reportId)).report
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function run(action: () => Promise<{ report: AdminReportDetail }>) {
  if (busy.value) return
  busy.value = true
  error.value = null
  try {
    report.value = (await action()).report
    emit('changed')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function act(action: ReportActionId) {
  const body = actionBody(action, { reason: '', minutes: null, keepOpen: keepOpen.value, includeRelated: includeRelated.value })
  if (action === 'sanction') {
    const d = draft.value
    Object.assign(body, {
      kind: d.kind,
      duration: d.duration,
      reasonCode: d.reasonCode || undefined,
      ...(d.duration === 'custom' ? { minutes: draftMinutes(d) ?? undefined } : {}),
      ...(d.reason.trim() ? { reason: d.reason.trim() } : {}),
      // Die API nimmt an Meldungen nur eine Zeile als Notiz.
      ...(d.note.trim() ? { note: d.note.trim().replace(/\s*\n+\s*/g, ' ') } : {}),
    })
  }
  void run(() => backend.social.adminReportAction(props.reportId, body)).then(() => {
    if (!error.value) {
      keepOpen.value = false
      includeRelated.value = false
      sanctioning.value = null
      draft.value = emptyDraft('warn')
    }
  })
}

function reviewSanction() {
  if (draftError.value) {
    error.value = t(`team.form.errors.${draftError.value}`)
    return
  }
  error.value = null
  sanctioning.value = 'confirm'
}

function setStatus(status: 'open' | 'in_review') {
  void run(() => backend.social.adminReportStatus(props.reportId, status))
}

function addNote() {
  const text = note.value.trim()
  if (!text) return
  void run(() => backend.social.adminReportNote(props.reportId, text)).then(() => {
    if (!error.value) note.value = ''
  })
}

async function unmute() {
  const target = report.value?.target
  if (!target || busy.value) return
  busy.value = true
  try {
    await backend.social.adminUnmute(target.uuid)
    emit('changed')
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

const statusClass = computed(() => {
  const r = report.value
  if (!r) return ''
  if (r.status === 'open') return 'bg-lamp-900 text-lamp-300'
  if (r.status === 'in_review') return 'bg-base-700 text-base-100'
  return r.outcome === 'actioned' ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-400'
})

function systemLabel(event: string | undefined): string {
  const known = ['group_created', 'member_added', 'member_removed', 'member_left', 'renamed', 'owner_changed'] as const
  return event && (known as readonly string[]).includes(event) ? t(`admin.mod.systemEvents.${event as (typeof known)[number]}`) : (event ?? '')
}

// Gemeldete Nachricht in die Mitte scrollen.
const log = useTemplateRef<HTMLElement>('log')
watch(report, () =>
  nextTick(() => {
    const box = log.value
    const focus = box?.querySelector<HTMLElement>('.chat-focus')
    if (box && focus) box.scrollTop = focus.offsetTop - box.clientHeight / 2 + focus.clientHeight / 2
  }),
)

function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (image.value) image.value = null
  else if (sanctioning.value) sanctioning.value = null
  else emit('close')
}
onMounted(() => {
  window.addEventListener('keydown', onKey)
  void load()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[60] flex items-start justify-center overflow-y-auto bg-black/70 p-6 backdrop-blur-[2px]" @mousedown.self="emit('close')">
      <div class="card w-full max-w-6xl bg-base-900 p-5 shadow-2xl" role="dialog" aria-modal="true" aria-labelledby="admin-report-title" data-testid="admin-report-dialog">
        <header class="flex flex-wrap items-center gap-3">
          <div class="min-w-0 flex-1">
            <p class="text-xs tracking-[0.18em] text-base-400 uppercase">{{ t('admin.mod.dialogTitle') }} · {{ report ? t(`social.report.titles.${report.kind}`) : '' }}</p>
            <h2 id="admin-report-title" class="display mt-1 truncate text-2xl text-base-50">{{ report ? reasonLabel(report.reason) : '…' }}</h2>
          </div>
          <span v-if="report?.priority === 'high' && report.status !== 'resolved'" class="badge bg-redstone-600/30 text-redstone-300">{{ t('team.reports.high') }}</span>
          <span v-if="report" class="badge" :class="statusClass">
            {{ report.status === 'resolved' && report.outcome ? t(`admin.mod.outcome.${report.outcome}`) : t(`admin.mod.status.${report.status}`) }}
          </span>
          <button class="btn-icon" :aria-label="t('common.actions.close')" @click="emit('close')"><SocialIcon name="close" class="size-4" /></button>
        </header>

        <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
        <div v-if="!report && !error" class="skeleton mt-6 h-64" />

        <div v-if="report" class="mt-5 grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <!-- Kontext -->
          <section class="min-w-0 space-y-5">
            <div>
              <h3 class="section-title">{{ t('admin.mod.context') }}</h3>
              <p v-if="report.evidence?.conversation" class="mt-1 text-xs text-base-400">
                {{ report.evidence.conversation.kind === 'group'
                  ? t('admin.mod.contextGroup', { name: report.evidence.conversation.name ?? '', n: report.evidence.conversation.members.length })
                  : t('admin.mod.contextDm') }}
                · {{ dateTime(report.evidence.capturedAt) }}
              </p>
              <p v-if="report.evidencePurged" class="mt-3 text-sm text-base-400">{{ t('admin.mod.purged') }}</p>
              <p v-else-if="!report.evidence?.messages.length" class="mt-3 text-sm text-base-400">{{ t('admin.mod.noContext') }}</p>
              <ol v-else ref="log" class="chat-log mt-3" data-testid="evidence-log">
                <li
                  v-for="msg in report.evidence!.messages"
                  :key="msg.id"
                  class="chat-line"
                  :class="{ 'chat-focus': msg.id === focusId, 'chat-target': msg.sender?.uuid === report.target?.uuid }"
                >
                  <div class="flex flex-wrap items-baseline gap-x-2 text-xs">
                    <span class="font-semibold" :class="msg.sender?.uuid === report.target?.uuid ? 'text-redstone-300' : 'text-base-100'">{{ msg.sender ? name(msg.sender) : '–' }}</span>
                    <span class="text-base-400">{{ dateTime(msg.createdAt) }}</span>
                    <span v-if="msg.editedAt" class="text-base-400">· {{ t('social.chat.edited') }}</span>
                    <span v-if="msg.deleted" class="text-base-400">· {{ t('social.chat.deleted') }}</span>
                  </div>
                  <p v-if="msg.kind === 'system'" class="text-xs text-base-400 italic">{{ t('admin.mod.system', { event: systemLabel(msg.system?.event) }) }}</p>
                  <p v-if="msg.text" class="msg-text">{{ msg.text }}</p>
                  <p v-if="msg.invite" class="text-xs text-lamp-300">{{ t('admin.mod.invite', { address: msg.invite.address }) }}</p>
                  <p v-if="msg.attachments.length" class="text-xs text-base-400">{{ t('admin.mod.images', msg.attachments.length) }}</p>
                </li>
              </ol>
            </div>

            <div v-if="report.evidence?.images.length">
              <h3 class="section-title">{{ t('admin.mod.reportedImages') }}</h3>
              <div class="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
                <button v-for="img in report.evidence.images" :key="img.id" class="evidence-img" @click="image = evidenceUrl(report!.id, img.id)">
                  <img :src="evidenceUrl(report.id, img.id)" :width="img.width" :height="img.height" alt="" loading="lazy" />
                </button>
              </div>
            </div>

            <div>
              <h3 class="section-title">{{ t('admin.mod.note') }}</h3>
              <p class="mt-2 text-sm whitespace-pre-wrap" :class="report.note ? 'text-base-100' : 'text-base-400'">{{ report.note || t('admin.mod.noNote') }}</p>
            </div>

            <div>
              <h3 class="section-title">{{ t('admin.mod.notes') }}</h3>
              <ul class="mt-2 space-y-2">
                <li v-for="n in report.notes" :key="n.id" class="rounded-md bg-base-950 px-3 py-2 text-sm">
                  <p class="text-xs text-base-400">{{ n.actorName || n.actor }} · {{ dateTime(n.at) }}</p>
                  <p class="mt-1 whitespace-pre-wrap text-base-100">{{ n.text }}</p>
                </li>
              </ul>
              <form class="mt-3 flex gap-2" @submit.prevent="addNote">
                <input v-model="note" class="field flex-1" maxlength="2000" :placeholder="t('admin.mod.notePlaceholder')" :aria-label="t('admin.mod.notePlaceholder')" />
                <button class="btn btn-ghost" :disabled="busy || !note.trim()">{{ t('admin.mod.addNote') }}</button>
              </form>
            </div>

            <div v-if="report.audit.length">
              <h3 class="section-title">{{ t('admin.mod.audit') }}</h3>
              <ul class="mt-2 space-y-1 text-xs text-base-400">
                <li v-for="(a, i) in report.audit" :key="i">
                  {{ dateTime(a.at) }} · <span class="text-base-200">{{ a.actorName || a.actor }}</span> · <span class="font-mono">{{ a.action }}</span>
                  <span v-if="a.detail"> · {{ a.detail }}</span>
                </li>
              </ul>
            </div>
          </section>

          <!-- Personen + Entscheidung -->
          <aside class="space-y-4">
            <div class="rounded-lg border border-base-800 p-4">
              <p class="text-xs text-base-400">{{ t('admin.mod.targetLabel') }}</p>
              <p class="mt-1 flex items-center gap-2 font-semibold text-base-50">
                <span v-if="report.target" class="block size-6 overflow-hidden rounded"><PlayerFace :uuid="report.target.uuid" :name="report.target.name" /></span>
                {{ name(report.target) }}
              </p>
              <p v-if="report.target" class="font-mono text-[11px] text-base-400">{{ report.target.uuid }}</p>
              <NuxtLink v-if="report.target" :to="`/admin/players/${report.target.uuid}`" class="mt-1 inline-block text-xs text-redstone-300 hover:underline" @click="emit('close')">
                {{ t('team.reports.openFile') }}
              </NuxtLink>
              <template v-if="report.targetModeration">
                <p class="mt-2 text-xs text-base-200">{{ t('admin.mod.targetStats', report.targetModeration.reports) }}</p>
                <p v-if="report.targetModeration.mute" class="mt-2 flex flex-wrap items-center gap-2 text-xs text-lamp-300">
                  {{ report.targetModeration.mute.expiresAt ? t('admin.mod.mutedUntil', { date: dateTime(report.targetModeration.mute.expiresAt) }) : t('admin.mod.mutedReview') }}
                  <button class="btn btn-ghost px-2 py-0.5 text-xs" :disabled="busy" @click="unmute">{{ t('admin.mod.unmute') }}</button>
                </p>
                <details v-if="report.targetModeration.sanctions.length" class="mt-2 text-xs">
                  <summary class="cursor-pointer text-base-200">{{ t('admin.mod.history') }}</summary>
                  <ul class="mt-1 space-y-1 text-base-400">
                    <li v-for="s in report.targetModeration.sanctions" :key="s.id">
                      {{ dateTime(s.createdAt) }} · {{ t(`admin.mod.sanction.${s.kind}`) }}<span v-if="s.auto"> ({{ t('admin.mod.auto') }})</span>
                      <span v-if="s.reason"> · {{ s.reason }}</span><span v-if="s.liftedAt"> · {{ t('admin.mod.lifted') }}</span>
                    </li>
                  </ul>
                </details>
              </template>
            </div>

            <div class="rounded-lg border border-base-800 p-4">
              <p class="text-xs text-base-400">{{ t('admin.mod.reporterLabel') }}</p>
              <p class="mt-1 flex flex-wrap items-center gap-2 font-semibold text-base-50">
                {{ name(report.reporter) }}
                <span v-if="report.lowTrust" class="badge bg-lamp-900 text-lamp-300">{{ t('admin.mod.lowTrust') }}</span>
              </p>
              <p v-if="report.reporterStats" class="mt-1 text-xs text-base-200">
                {{ t('admin.mod.reporterStats', { actioned: report.reporterStats.actioned, dismissed: report.reporterStats.dismissed, open: report.reporterStats.open }) }}
              </p>
              <p class="mt-1 text-xs text-base-400">{{ dateTime(report.createdAt) }}</p>
            </div>

            <div class="rounded-lg border border-base-800 p-4">
              <h3 class="section-title">{{ t('admin.mod.decision') }}</h3>
              <div v-if="!resolved" class="mt-3 flex gap-2">
                <button v-if="report.status === 'open'" class="btn btn-ghost text-xs" :disabled="busy" @click="setStatus('in_review')">{{ t('admin.mod.claim') }}</button>
                <button v-else class="btn btn-ghost text-xs" :disabled="busy" @click="setStatus('open')">{{ t('admin.mod.reopen') }}</button>
              </div>
              <label class="mt-3 flex items-start gap-2 text-xs text-base-200">
                <input v-model="keepOpen" type="checkbox" class="mt-0.5 accent-redstone-500" />{{ t('admin.mod.keepOpen') }}
              </label>
              <label class="mt-2 flex items-start gap-2 text-xs text-base-200">
                <input v-model="includeRelated" type="checkbox" class="mt-0.5 accent-redstone-500" />{{ t('admin.mod.includeRelated') }}
              </label>
              <div class="mt-4 grid grid-cols-2 gap-2">
                <button v-if="report.messageId" class="btn btn-danger col-span-2 text-sm" :disabled="busy" data-testid="mod-delete" @click="act('delete_message')">
                  <SocialIcon name="trash" class="size-4" />{{ t('admin.mod.deleteMessage') }}
                </button>
                <button
                  class="btn btn-ghost col-span-2 text-sm"
                  :class="{ 'ring-1 ring-redstone-500': sanctioning }"
                  :disabled="busy || !report.target"
                  data-testid="mod-sanction"
                  @click="sanctioning = sanctioning ? null : 'form'"
                >
                  <SocialIcon name="gavel" class="size-4" />{{ t('team.reports.sanction') }}
                </button>
                <button class="btn btn-ghost text-sm" :disabled="busy || resolved" data-testid="mod-dismiss" @click="act('dismiss')">{{ t('admin.mod.dismiss') }}</button>
                <button class="btn btn-primary text-sm" :disabled="busy || resolved" @click="act('resolve')">
                  <SocialIcon name="check" class="size-4" />{{ t('admin.mod.resolve') }}
                </button>
              </div>
            </div>

            <div v-if="sanctioning && report.target" class="rounded-lg border border-redstone-600/50 p-4" data-testid="mod-sanction-form">
              <h3 class="section-title mb-3">{{ t('team.reports.sanctionFor', { name: name(report.target) }) }}</h3>
              <AdminSanctionForm v-if="sanctioning === 'form'" v-model="draft" :limits="team.limits.value" compact />
              <div v-else class="space-y-2 text-xs">
                <p class="flex flex-wrap items-center gap-2"><SanctionKindBadge :kind="draft.kind" /><span class="text-base-200">{{ t(`team.durations.${draft.duration}`) }}</span></p>
                <p class="text-base-200">{{ t('team.sanction.confirmLead', { name: name(report.target) }) }}</p>
                <p v-if="draft.kind === 'account_ban'" class="rounded-md bg-redstone-900 px-3 py-2 text-redstone-300">{{ t('team.sanction.banWarning') }}</p>
              </div>
              <div class="mt-3 flex justify-end gap-2">
                <button class="btn btn-ghost text-xs" :disabled="busy" @click="sanctioning === 'confirm' ? (sanctioning = 'form') : (sanctioning = null)">
                  {{ sanctioning === 'confirm' ? t('team.common.back') : t('common.actions.cancel') }}
                </button>
                <button v-if="sanctioning === 'form'" class="btn btn-primary text-xs" data-testid="mod-sanction-next" @click="reviewSanction">{{ t('team.sanction.review') }}</button>
                <button v-else :class="draft.kind === 'account_ban' ? 'btn btn-danger text-xs' : 'btn btn-primary text-xs'" :disabled="busy" data-testid="mod-sanction-submit" @click="act('sanction')">
                  {{ t('team.sanction.submit') }}
                </button>
              </div>
            </div>

            <div v-if="report.related.length" class="rounded-lg border border-base-800 p-4">
              <h3 class="section-title">{{ t('admin.mod.related') }}</h3>
              <ul class="mt-2 space-y-1.5 text-xs">
                <li v-for="r in report.related" :key="r.id">
                  <button class="text-left text-base-200 hover:text-base-50" @click="emit('open', r.id)">
                    {{ dateTime(r.createdAt) }} · {{ reasonLabel(r.reason) }} · {{ t(`admin.mod.status.${r.status}`) }}
                  </button>
                </li>
              </ul>
            </div>
          </aside>
        </div>
      </div>
      <SocialLightbox v-if="image" :urls="[image]" :start="0" @close="image = null" />
    </div>
  </Teleport>
</template>

<style scoped>
.chat-log {
  max-height: 32rem;
  overflow-y: auto;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.5rem;
}
.chat-line {
  padding: 0.375rem 0.625rem;
  border-radius: 0.375rem;
}
.chat-target {
  background: color-mix(in srgb, var(--color-redstone-900) 45%, transparent);
}
.chat-focus {
  box-shadow: inset 3px 0 0 var(--color-lamp-400);
  background: color-mix(in srgb, var(--color-lamp-900) 55%, transparent);
}
.msg-text {
  margin-top: 0.125rem;
  font-size: 0.875rem;
  color: var(--color-base-50);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.evidence-img {
  display: grid;
  place-items: center;
  overflow: hidden;
  aspect-ratio: 4 / 3;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
}
.evidence-img img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}
</style>
