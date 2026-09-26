<script setup lang="ts">
// Prüf-Dialog für eine Chat-Meldung: Kontext (Nachrichten davor/danach), gemeldete Bilder,
// Moderationsstand von Ziel und Melder, Notizen, Audit-Log und die Entscheidung.
const props = defineProps<{ reportId: string }>()
const emit = defineEmits<{ close: [], changed: [], open: [id: string] }>()

const { m, fill, lang } = useLang()
const { api } = useAdmin()
const t = computed(() => m.value.admin.mod)

const report = ref<ReportDetail | null>(null)
const error = ref('')
const busy = ref(false)

async function load() {
  error.value = ''
  try {
    report.value = (await api<{ report: ReportDetail }>(`/v1/admin/reports/${props.reportId}`)).report
  } catch (e) {
    error.value = fill(m.value.admin.failed, { error: apiMessage(e) })
  }
}

// --- Entscheidung ----------------------------------------------------------------------------
const reason = ref('')
const minutes = ref<'60' | '1440' | '10080' | '43200' | 'forever'>('1440')
const keepOpen = ref(false)
const includeRelated = ref(false)
const note = ref('')
const confirmBan = ref(false)

function resetForm() {
  reason.value = ''
  minutes.value = '1440'
  keepOpen.value = false
  includeRelated.value = false
  note.value = ''
  confirmBan.value = false
}

// Nach den Formular-Refs (sonst Zugriff vor der Initialisierung beim sofortigen Aufruf).
watch(() => props.reportId, () => {
  report.value = null
  resetForm()
  void load()
}, { immediate: true })

async function run(fn: () => Promise<{ report: ReportDetail }>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    report.value = (await fn()).report
    emit('changed')
  } catch (e) {
    error.value = fill(m.value.admin.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}

function act(action: ReportAction) {
  if (action === 'ban' && !confirmBan.value) {
    confirmBan.value = true
    return
  }
  confirmBan.value = false
  const body: Record<string, unknown> = { action }
  const r = reason.value.trim().slice(0, 200)
  if (r && (action === 'warn' || action === 'mute' || action === 'ban')) body.reason = r
  if (action === 'mute' && minutes.value !== 'forever') body.minutes = Number(minutes.value)
  if (keepOpen.value && action !== 'dismiss' && action !== 'resolve') body.keepOpen = true
  if (includeRelated.value) body.includeRelated = true
  void run(() => api(`/v1/admin/reports/${props.reportId}/actions`, { method: 'POST', body })).then(() => {
    if (!error.value) resetForm()
  })
}

function setStatus(status: 'open' | 'in_review') {
  void run(() => api(`/v1/admin/reports/${props.reportId}/status`, { method: 'POST', body: { status } }))
}

function addNote() {
  const text = note.value.trim().slice(0, 2000)
  if (!text) return
  void run(() => api(`/v1/admin/reports/${props.reportId}/notes`, { method: 'POST', body: { text } })).then(() => {
    if (!error.value) note.value = ''
  })
}

async function unmute() {
  const target = report.value?.target
  if (!target || busy.value) return
  busy.value = true
  try {
    await api(`/v1/admin/moderation/users/${target.uuid}/mute`, { method: 'DELETE' })
    emit('changed')
    await load()
  } catch (e) {
    error.value = fill(m.value.admin.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}

const resolved = computed(() => report.value?.status === 'resolved')
const systemLabel = (e: string | undefined) => (e && e in t.value.systemEvents ? t.value.systemEvents[e as keyof typeof t.value.systemEvents] : (e ?? ''))

// Gemeldete Nachricht im Verlauf sichtbar machen (nur die Liste scrollen, nicht die Seite).
const log = shallowRef<HTMLElement | null>(null)
watch(report, () => void nextTick(() => {
  const box = log.value
  const focus = box?.querySelector<HTMLElement>('.chat-focus')
  if (box && focus) box.scrollTop = focus.offsetTop - box.clientHeight / 2 + focus.clientHeight / 2
}))
const focusId = computed(() => report.value?.evidence?.focus ?? null)
const name = (p: PlayerRef | null | undefined) => p?.name || t.value.unknown
const when = (iso: string | null | undefined) => dateTime(iso, lang.value)
const statusClass = computed(() => {
  const r = report.value
  if (!r) return ''
  if (r.status === 'open') return 'bg-lamp-900 text-lamp-300'
  if (r.status === 'in_review') return 'bg-base-700 text-base-100'
  return r.outcome === 'actioned' ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-400'
})

// --- Dialog-Verhalten (wie der Umhang-Dialog) ----------------------------------------------------
const panel = shallowRef<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null
function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape' || e.defaultPrevented) return
  e.preventDefault()
  if (confirmBan.value) confirmBan.value = false
  else emit('close')
}
function trapTab(e: KeyboardEvent) {
  if (e.key !== 'Tab' || !panel.value) return
  const items = [...panel.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter((el) => el.offsetParent !== null)
  if (!items.length) return
  const first = items[0]!
  const last = items[items.length - 1]!
  if (e.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}
let overflow = ''
onMounted(() => {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  overflow = document.documentElement.style.overflow
  document.documentElement.style.overflow = 'hidden'
  window.addEventListener('keydown', onKey)
  void nextTick(() => panel.value?.focus())
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.documentElement.style.overflow = overflow
  if (returnFocus && document.contains(returnFocus)) returnFocus.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="backdrop" @mousedown.self="emit('close')">
      <div
        ref="panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="report-review-title"
        tabindex="-1"
        class="review-panel card w-full max-w-6xl animate-pop p-4 sm:p-6"
        @keydown="trapTab"
      >
        <header class="flex flex-wrap items-center gap-3">
          <div class="min-w-0 flex-1">
            <p class="text-xs tracking-[0.18em] text-base-400 uppercase">{{ t.dialogTitle }} · {{ report ? t.kinds[report.kind] : '' }}</p>
            <h2 id="report-review-title" class="display mt-1 truncate text-2xl text-base-50">
              {{ report ? t.reasons[report.reason] : '…' }}
            </h2>
          </div>
          <span v-if="report" class="badge" :class="statusClass">
            {{ report.status === 'resolved' && report.outcome ? t.outcome[report.outcome] : t.status[report.status] }}
          </span>
          <button type="button" class="btn-icon" :aria-label="m.common.close" :title="`${m.common.close} (Esc)`" @click="emit('close')">
            <SiteIcon name="close" class="size-4" />
          </button>
        </header>

        <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
        <div v-if="!report && !error" class="skeleton mt-6 h-64 rounded-lg" />

        <div v-if="report" class="mt-5 grid gap-6 lg:grid-cols-[minmax(0,1fr)_21rem]">
          <!-- Kontext -->
          <section class="min-w-0 space-y-5">
            <div>
              <h3 class="section-title">{{ t.context }}</h3>
              <p v-if="report.evidence?.conversation" class="mt-1 text-xs text-base-400">
                {{ report.evidence.conversation.kind === 'group'
                  ? fill(t.contextGroup, { name: report.evidence.conversation.name ?? '', n: report.evidence.conversation.members.length })
                  : t.contextDm }}
                · {{ when(report.evidence.capturedAt) }}
              </p>
              <p v-if="report.evidencePurged" class="mt-3 text-sm text-base-400">{{ t.purged }}</p>
              <p v-else-if="!report.evidence?.messages.length" class="mt-3 text-sm text-base-400">{{ t.noContext }}</p>
              <ol v-else ref="log" class="chat-log mt-3">
                <li
                  v-for="msg in report.evidence!.messages"
                  :key="msg.id"
                  class="chat-line"
                  :class="{ 'chat-focus': msg.id === focusId, 'chat-target': msg.sender?.uuid === report.target?.uuid }"
                >
                  <div class="flex flex-wrap items-baseline gap-x-2 text-xs">
                    <span class="font-semibold" :class="msg.sender?.uuid === report.target?.uuid ? 'text-redstone-300' : 'text-base-100'">
                      {{ msg.sender ? name(msg.sender) : '–' }}
                    </span>
                    <span class="text-base-400">{{ when(msg.createdAt) }}</span>
                    <span v-if="msg.editedAt" class="text-base-400">· {{ t.edited }}</span>
                    <span v-if="msg.deleted" class="text-base-400">· {{ t.deleted }}</span>
                  </div>
                  <p v-if="msg.kind === 'system'" class="text-xs text-base-400 italic">{{ fill(t.system, { event: systemLabel(msg.system?.event) }) }}</p>
                  <p v-if="msg.text" class="msg-text">{{ msg.text }}</p>
                  <p v-if="msg.invite" class="text-xs text-lamp-300">{{ fill(t.invite, { address: msg.invite.address }) }}</p>
                  <p v-if="msg.world" class="text-xs text-lamp-300">{{ fill(t.world, { name: msg.world.name }) }}</p>
                  <p v-if="msg.attachments.length" class="text-xs text-base-400">{{ fill(t.attachments, { n: msg.attachments.length }) }}</p>
                </li>
              </ol>
            </div>

            <div v-if="report.evidence?.images.length">
              <h3 class="section-title">{{ t.reportedImages }}</h3>
              <div class="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
                <a v-for="img in report.evidence.images" :key="img.id" :href="img.path" target="_blank" rel="noopener" class="evidence-img">
                  <img :src="img.path" :width="img.width" :height="img.height" alt="" loading="lazy" />
                </a>
              </div>
            </div>

            <div>
              <h3 class="section-title">{{ t.note }}</h3>
              <p class="mt-2 text-sm whitespace-pre-wrap" :class="report.note ? 'text-base-100' : 'text-base-400'">{{ report.note || t.noNote }}</p>
            </div>

            <div>
              <h3 class="section-title">{{ t.notes }}</h3>
              <ul class="mt-2 space-y-2">
                <li v-for="n in report.notes" :key="n.id" class="rounded-md bg-base-950 px-3 py-2 text-sm">
                  <p class="text-xs text-base-400">{{ n.actorName || n.actor }} · {{ when(n.at) }}</p>
                  <p class="mt-1 whitespace-pre-wrap text-base-100">{{ n.text }}</p>
                </li>
              </ul>
              <form class="mt-3 flex gap-2" @submit.prevent="addNote">
                <input v-model="note" class="field flex-1" maxlength="2000" :placeholder="t.notePlaceholder" />
                <button type="submit" class="btn btn-ghost" :disabled="busy || !note.trim()">{{ t.addNote }}</button>
              </form>
            </div>

            <div v-if="report.audit.length">
              <h3 class="section-title">{{ t.audit }}</h3>
              <ul class="mt-2 space-y-1 text-xs text-base-400">
                <li v-for="(a, i) in report.audit" :key="i">
                  {{ when(a.at) }} · <span class="text-base-200">{{ a.actorName || a.actor }}</span> · <span class="font-mono">{{ a.action }}</span>
                  <span v-if="a.detail"> · {{ a.detail }}</span>
                </li>
              </ul>
            </div>
          </section>

          <!-- Personen + Entscheidung -->
          <aside class="space-y-5">
            <div class="rounded-lg border border-base-800 p-4">
              <p class="text-xs text-base-400">{{ t.target }}</p>
              <p class="mt-1 font-semibold text-base-50">{{ name(report.target) }}</p>
              <p v-if="report.target" class="font-mono text-[11px] text-base-400">{{ report.target.uuid }}</p>
              <template v-if="report.targetModeration">
                <p class="mt-2 text-xs text-base-300">{{ fill(t.targetStats, report.targetModeration.reports) }}</p>
                <p v-if="report.targetModeration.mute" class="mt-2 flex flex-wrap items-center gap-2 text-xs text-lamp-300">
                  {{ report.targetModeration.mute.expiresAt ? fill(t.mutedUntil, { date: when(report.targetModeration.mute.expiresAt) }) : t.mutedReview }}
                  <button type="button" class="btn btn-ghost px-2 py-0.5 text-xs" :disabled="busy" @click="unmute">{{ t.unmute }}</button>
                </p>
                <details v-if="report.targetModeration.sanctions.length" class="mt-2 text-xs">
                  <summary class="cursor-pointer text-base-300">{{ t.history }}</summary>
                  <ul class="mt-1 space-y-1 text-base-400">
                    <li v-for="s in report.targetModeration.sanctions" :key="s.id">
                      {{ when(s.createdAt) }} · {{ t.sanction[s.kind] }}<span v-if="s.auto"> ({{ t.auto }})</span>
                      <span v-if="s.reason"> · {{ s.reason }}</span><span v-if="s.liftedAt"> · {{ t.lifted }}</span>
                    </li>
                  </ul>
                </details>
              </template>
            </div>

            <div class="rounded-lg border border-base-800 p-4">
              <p class="text-xs text-base-400">{{ t.reporter }}</p>
              <p class="mt-1 flex flex-wrap items-center gap-2 font-semibold text-base-50">
                {{ name(report.reporter) }}
                <span v-if="report.lowTrust" class="badge bg-lamp-900 text-lamp-300">{{ t.lowTrust }}</span>
              </p>
              <p v-if="report.reporterStats" class="mt-1 text-xs text-base-300">{{ fill(t.reporterStats, { actioned: report.reporterStats.actioned, dismissed: report.reporterStats.dismissed, open: report.reporterStats.open }) }}</p>
              <p class="mt-1 text-xs text-base-400">{{ when(report.createdAt) }}</p>
            </div>

            <div class="rounded-lg border border-base-800 p-4">
              <h3 class="section-title">{{ t.decision }}</h3>
              <div v-if="!resolved" class="mt-3 flex flex-wrap gap-2">
                <button v-if="report.status === 'open'" type="button" class="btn btn-ghost text-xs" :disabled="busy" @click="setStatus('in_review')">{{ t.claim }}</button>
                <button v-else type="button" class="btn btn-ghost text-xs" :disabled="busy" @click="setStatus('open')">{{ t.reopen }}</button>
              </div>
              <label class="label mt-3" for="mod-reason">{{ t.reason }}</label>
              <input id="mod-reason" v-model="reason" class="field" maxlength="200" />
              <label class="label mt-3" for="mod-duration">{{ t.duration }}</label>
              <select id="mod-duration" v-model="minutes" class="field">
                <option value="60">{{ t.durations.d60 }}</option>
                <option value="1440">{{ t.durations.d1440 }}</option>
                <option value="10080">{{ t.durations.d10080 }}</option>
                <option value="43200">{{ t.durations.d43200 }}</option>
                <option value="forever">{{ t.durations.forever }}</option>
              </select>
              <label class="mt-3 flex items-start gap-2 text-xs text-base-300">
                <input v-model="keepOpen" type="checkbox" class="mt-0.5" />{{ t.keepOpen }}
              </label>
              <label class="mt-2 flex items-start gap-2 text-xs text-base-300">
                <input v-model="includeRelated" type="checkbox" class="mt-0.5" />{{ t.includeRelated }}
              </label>
              <div class="mt-4 grid grid-cols-2 gap-2">
                <button v-if="report.messageId" type="button" class="btn btn-danger col-span-2 text-sm" :disabled="busy" @click="act('delete_message')">
                  <SiteIcon name="trash" class="size-4" />{{ t.deleteMessage }}
                </button>
                <button type="button" class="btn btn-ghost text-sm" :disabled="busy || !report.target" @click="act('warn')">{{ t.warn }}</button>
                <button type="button" class="btn btn-ghost text-sm" :disabled="busy || !report.target" @click="act('mute')">{{ t.mute }}</button>
                <button type="button" class="btn btn-danger text-sm" :disabled="busy || !report.target" @click="act('ban')">{{ t.ban }}</button>
                <button type="button" class="btn btn-ghost text-sm" :disabled="busy || resolved" @click="act('dismiss')">{{ t.dismiss }}</button>
                <button type="button" class="btn btn-primary col-span-2 text-sm" :disabled="busy || resolved" @click="act('resolve')">
                  <SiteIcon name="check" class="size-4" />{{ t.resolve }}
                </button>
              </div>
              <p v-if="confirmBan" role="alert" class="mt-3 rounded-md bg-redstone-900 p-3 text-xs text-redstone-300">
                {{ fill(t.confirmBan, { name: name(report.target) }) }}
                <button type="button" class="btn btn-danger mt-2 w-full text-xs" :disabled="busy" @click="act('ban')">{{ t.ban }}</button>
              </p>
            </div>

            <div v-if="report.related.length" class="rounded-lg border border-base-800 p-4">
              <h3 class="section-title">{{ t.related }}</h3>
              <ul class="mt-2 space-y-1.5 text-xs">
                <li v-for="r in report.related" :key="r.id">
                  <button type="button" class="text-left text-base-200 hover:text-base-50" @click="emit('open', r.id)">
                    {{ when(r.createdAt) }} · {{ t.reasons[r.reason] }} · {{ t.status[r.status] }}
                  </button>
                </li>
              </ul>
            </div>
          </aside>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  overflow-y: auto;
  padding: 0.75rem;
  background: rgb(0 0 0 / 0.72);
  backdrop-filter: blur(3px);
}
@media (min-width: 640px) {
  .backdrop {
    padding: 2rem 1.5rem;
  }
}
.review-panel:focus,
.review-panel:focus-visible {
  outline: none;
}
.chat-log {
  position: relative;
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
