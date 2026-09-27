<script setup lang="ts">
// Einreichung prüfen (§25.5): Vorschau im Editor (vor dem Annehmen bearbeitbar, ID vorgeschlagen), Angaben des
// Erstellers, Annehmen (veröffentlicht oder Entwurf) oder Ablehnen mit Grund für den Ersteller.
import type { CircuitData } from '#shared/circuits'
import type { AdminCircuitDetail, AdminSubmission } from '~/utils/circuits/types'
import { circuitErrorDetail } from '~/utils/circuits/types'

const { a, when } = useAdminText()
const { c, fill } = useCircuitText()
const { api } = useAdmin()
const route = useRoute()
const router = useRouter()

const id = computed(() => String(route.params.id))
const sub = ref<AdminSubmission | null>(null)
const initial = shallowRef<CircuitData | null>(null)
const editor = shallowRef<{ current: CircuitData | string, check: { ok: boolean } } | null>(null)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const rejecting = ref(false)
const reason = ref('')

async function load() {
  error.value = ''
  try {
    const r = await api<{ submission: AdminSubmission, suggestedId: string | null }>(`/v1/admin/circuit-submissions/${id.value}`)
    sub.value = r.submission
    initial.value = { ...r.submission.circuit, id: r.suggestedId ?? r.submission.circuit.id }
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}
onMounted(load)

const pending = computed(() => sub.value?.status === 'pending')
const valid = computed(() => !!editor.value?.check.ok)

async function accept(status: 'published' | 'draft') {
  const circuit = editor.value?.current
  if (!circuit || typeof circuit === 'string' || !valid.value) return
  busy.value = true
  error.value = ''
  try {
    const r = await api<{ submission: AdminSubmission, circuit: AdminCircuitDetail }>(`/v1/admin/circuit-submissions/${id.value}/accept`, { method: 'POST', body: { circuit, status } })
    sub.value = r.submission
    notice.value = fill(c.value.adm.accepted, { id: r.circuit.id })
    await router.push(`/admin/circuits/${r.circuit.id}`)
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: circuitErrorDetail(e) || apiMessage(e) })
  } finally {
    busy.value = false
  }
}

async function reject() {
  const text = reason.value.trim()
  if (text.length < 3) return
  busy.value = true
  error.value = ''
  try {
    const r = await api<{ submission: AdminSubmission }>(`/v1/admin/circuit-submissions/${id.value}/reject`, { method: 'POST', body: { reason: text.slice(0, 500) } })
    sub.value = r.submission
    rejecting.value = false
    notice.value = c.value.adm.rejected
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="adm-page">
    <NuxtLink to="/admin/circuits?tab=submissions" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50"><SiteIcon name="back" class="size-4" />{{ c.adm.back }}</NuxtLink>
    <header class="mt-3">
      <p class="text-xs tracking-[0.14em] text-base-400 uppercase">{{ c.adm.reviewTitle }}</p>
      <h1 class="adm-title">{{ sub?.name ?? '…' }}</h1>
    </header>
    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="notice" role="status" class="mt-3 text-sm text-ok">{{ notice }}</p>
    <div v-if="!sub && !error" class="skeleton mt-6 h-96 rounded-xl" />

    <template v-if="sub">
      <section class="card mt-5 grid gap-4 p-5 md:grid-cols-[1fr_auto]">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-3">
            <PlayerHead :uuid="sub.submitter.uuid" :name="sub.submitter.name" :size="32" />
            <div>
              <p class="text-sm text-base-50">{{ c.adm.submitter }}: <NuxtLink :to="`/admin/players/${sub.submitter.uuid}`" class="font-semibold hover:underline">{{ sub.submitter.name }}</NuxtLink></p>
              <p class="text-xs text-base-400">{{ when(sub.createdAt) }} · {{ fill(c.adm.format, { format: sub.format }) }} · {{ c.categories[sub.category] }} · {{ sub.lang.toUpperCase() }}</p>
              <p class="text-xs text-base-400">{{ fill(c.adm.stats, sub.submitterStats) }}</p>
            </div>
          </div>
          <h2 class="section-title mt-4">{{ c.adm.description }}</h2>
          <p class="mt-1 text-sm whitespace-pre-line text-base-200">{{ sub.description }}</p>
        </div>
        <div class="flex flex-col gap-2 md:w-60">
          <template v-if="pending">
            <button type="button" class="btn btn-primary" :disabled="busy || !valid" @click="accept('published')"><SiteIcon name="check" class="size-4" />{{ c.adm.accept }}</button>
            <button type="button" class="btn btn-ghost" :disabled="busy || !valid" @click="accept('draft')">{{ c.adm.acceptDraft }}</button>
            <button type="button" class="btn btn-danger" :disabled="busy" @click="rejecting = true"><SiteIcon name="close" class="size-4" />{{ c.adm.reject }}</button>
          </template>
          <template v-else>
            <p class="text-sm text-base-300">{{ fill(c.adm.decided, { status: c.adm.subStatus[sub.status] ?? sub.status }) }}</p>
            <p v-if="sub.reason" class="rounded-md border border-base-800 bg-base-950 px-3 py-2 text-sm whitespace-pre-line text-base-200">{{ sub.reason }}</p>
            <NuxtLink v-if="sub.circuitId" :to="`/admin/circuits/${sub.circuitId}`" class="btn btn-ghost">{{ c.adm.openCircuit }}</NuxtLink>
          </template>
        </div>
      </section>

      <CircuitEditor v-if="initial" ref="editor" class="mt-5" :initial="initial" :id-editable="pending" :readonly="!pending" />
    </template>

    <AdminConfirm
      v-if="rejecting"
      :title="c.adm.reject"
      danger
      :busy="busy || reason.trim().length < 3"
      :confirm-label="c.adm.reject"
      @cancel="rejecting = false"
      @confirm="reject"
    >
      <label class="label" for="reject-reason">{{ c.adm.rejectReason }}</label>
      <textarea id="reject-reason" v-model="reason" class="field min-h-28" maxlength="500" :placeholder="c.adm.rejectPlaceholder" />
    </AdminConfirm>
  </div>
</template>
