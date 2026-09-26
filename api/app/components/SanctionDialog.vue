<script setup lang="ts">
// Neue Strafe verhängen: Spieler (fest oder per Name/UUID), Formular, dann Bestätigung mit Zusammenfassung.
const props = withDefaults(defineProps<{ player?: { uuid: string, name: string | null } | null, kind?: SanctionKind, reportId?: string | null }>(), {
  player: null,
  kind: 'chat_mute',
  reportId: null,
})
const emit = defineEmits<{ close: [], done: [sanction: AdminSanction] }>()
const { a, fill } = useAdminText()
const { api, session } = useAdmin()

const draft = ref(newSanctionDraft(props.kind))
const form = shallowRef<{ valid: boolean } | null>(null)
const target = ref(props.player)
const lookup = ref('')
const lookupError = ref('')
const confirming = ref(false)
const busy = ref(false)
const error = ref('')
const limits = computed(() => session.value?.limits ?? { kinds: [], maxMinutes: 0, maxWarnMinutes: 0, permanent: false })

async function resolve() {
  lookupError.value = ''
  const q = lookup.value.trim()
  if (!q) return
  try {
    const r = await api<{ user: { uuid: string, name: string | null } }>(`/v1/admin/users/${encodeURIComponent(q)}`)
    target.value = { uuid: r.user.uuid, name: r.user.name }
  } catch {
    // Auch Konten, die TRS nie benutzt haben, lassen sich vorsorglich per UUID bestrafen.
    const plain = q.replace(/-/g, '').toLowerCase()
    if (/^[0-9a-f]{32}$/.test(plain)) target.value = { uuid: plain, name: null }
    else lookupError.value = a.value.common.noResults
  }
}

const summary = computed(() => {
  const d = draft.value
  const dur = d.duration === 'custom' ? `${d.customValue} ${a.value.customUnit[d.customUnit]}` : a.value.durations[d.duration]
  return fill(a.value.decision.confirmText, {
    kind: a.value.kinds[d.kind] ?? d.kind,
    name: target.value?.name || target.value?.uuid || '?',
    duration: dur ?? '',
    reason: a.value.reasons[d.reasonCode] ?? d.reasonCode,
  })
})

async function submit() {
  if (!target.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const r = await api<{ sanction: AdminSanction }>('/v1/admin/sanctions', {
      method: 'POST',
      body: { uuid: target.value.uuid, ...draftBody(draft.value), ...(props.reportId ? { reportId: props.reportId } : {}) },
    })
    emit('done', r.sanction)
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
    confirming.value = false
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AdminDialog :title="a.sanctions.newSanction" :kicker="target ? (target.name || target.uuid) : ''" size="lg" @close="emit('close')">
    <div v-if="!target" class="mb-5">
      <label class="label" for="sd-target">{{ a.sanctions.target }}</label>
      <form class="flex gap-2" @submit.prevent="resolve">
        <input id="sd-target" v-model="lookup" class="field" maxlength="36" autofocus />
        <button type="submit" class="btn btn-ghost">{{ a.common.searchShort }}</button>
      </form>
      <p v-if="lookupError" class="mt-1 text-xs text-redstone-300">{{ lookupError }}</p>
    </div>
    <div v-else class="mb-5 flex items-center gap-3 rounded-lg border border-base-800 p-3">
      <PlayerHead :uuid="target.uuid" :name="target.name" :size="36" />
      <div class="min-w-0">
        <p class="font-semibold text-base-50">{{ target.name || a.common.unknown }}</p>
        <p class="adm-mono truncate text-base-400">{{ target.uuid }}</p>
      </div>
      <button v-if="!player" type="button" class="btn btn-ghost ml-auto text-xs" @click="target = null">{{ a.common.back }}</button>
    </div>

    <SanctionForm ref="form" v-model="draft" :limits="limits" />
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <template #footer>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ a.common.cancel }}</button>
      <button type="button" class="btn btn-danger" :disabled="!target || !form?.valid || busy" @click="confirming = true">
        <SiteIcon name="gavel" class="size-4" />{{ a.decision.apply }}
      </button>
    </template>

    <AdminConfirm
      v-if="confirming"
      :title="a.decision.confirmTitle"
      :text="summary"
      :confirm-label="a.decision.apply"
      danger
      :busy="busy"
      :error="error"
      @cancel="confirming = false"
      @confirm="submit"
    />
  </AdminDialog>
</template>
