<script setup lang="ts">
// Strafe aufheben oder ihre Dauer ändern (verkürzen/verlängern), immer mit Begründung für den Verlauf.
const props = defineProps<{ sanction: AdminSanction, mode: 'lift' | 'duration' }>()
const emit = defineEmits<{ close: [], done: [sanction: AdminSanction] }>()
const { a, fill, when } = useAdminText()
const { api, session, isAdmin } = useAdmin()

const reason = ref('')
const permanent = ref(false)
const end = ref(toLocalInput(props.sanction.endsAt ?? new Date(Date.now() + 86_400_000).toISOString()))
const busy = ref(false)
const error = ref('')

/** Schnellwahl: neues Ende relativ zum Beginn der Strafe. */
const quick = computed(() => ['1h', '6h', '1d', '3d', '7d', '30d'].map((p) => ({
  p,
  iso: new Date(new Date(props.sanction.createdAt).getTime() + DURATION_MINUTES[p]! * 60_000).toISOString(),
})))
const maxMinutes = computed(() => (props.sanction.kind === 'warn' ? session.value?.limits.maxWarnMinutes : session.value?.limits.maxMinutes) ?? null)
function quickAllowed(iso: string): boolean {
  if (new Date(iso).getTime() <= Date.now()) return false
  const m = maxMinutes.value
  return m === null || (new Date(iso).getTime() - new Date(props.sanction.createdAt).getTime()) / 60_000 <= m
}

async function submit() {
  if (busy.value || !reason.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    const path = `/v1/admin/sanctions/${props.sanction.id}/${props.mode === 'lift' ? 'lift' : 'duration'}`
    const body = props.mode === 'lift'
      ? { reason: reason.value.trim().slice(0, 500) }
      : { reason: reason.value.trim().slice(0, 500), endsAt: permanent.value ? null : fromLocalInput(end.value) }
    const r = await api<{ sanction: AdminSanction }>(path, { method: 'POST', body })
    emit('done', r.sanction)
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AdminDialog :title="mode === 'lift' ? a.sanctions.liftTitle : a.sanctions.changeTitle" :kicker="`${a.kinds[sanction.kind]} · ${sanction.player.name || sanction.player.uuid}`" size="md" @close="emit('close')">
    <dl class="adm-dl">
      <dt>{{ a.sanctions.started }}</dt><dd>{{ when(sanction.createdAt) }}</dd>
      <dt>{{ a.sanctions.ends }}</dt><dd>{{ sanction.endsAt ? when(sanction.endsAt) : a.common.permanent }}</dd>
      <dt>{{ a.decision.reasonCode }}</dt><dd>{{ a.reasons[sanction.reasonCode] }}</dd>
    </dl>

    <div v-if="mode === 'duration'" class="mt-5">
      <p class="label">{{ a.sanctions.newEnd }}</p>
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="q in quick"
          :key="q.p"
          type="button"
          class="adm-chip-btn"
          :disabled="!quickAllowed(q.iso)"
          :aria-pressed="!permanent && fromLocalInput(end) === new Date(toLocalInput(q.iso)).toISOString()"
          @click="permanent = false; end = toLocalInput(q.iso)"
        >
          {{ a.durations[q.p] }}
        </button>
        <button v-if="isAdmin" type="button" class="adm-chip-btn" :aria-pressed="permanent" @click="permanent = !permanent">{{ a.sanctions.makePermanent }}</button>
      </div>
      <input v-if="!permanent" v-model="end" type="datetime-local" class="field mt-2" :aria-label="a.sanctions.newEnd" />
    </div>

    <label class="label mt-5" for="scd-reason">{{ a.sanctions.reasonForChange }} *</label>
    <textarea id="scd-reason" v-model="reason" class="field min-h-20" maxlength="500" autofocus />
    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>

    <template #footer>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ a.common.cancel }}</button>
      <button type="button" class="btn" :class="mode === 'lift' ? 'btn-primary' : 'btn-danger'" :disabled="busy || !reason.trim()" @click="submit">
        {{ mode === 'lift' ? a.sanctions.lift : a.common.save }}
      </button>
    </template>
  </AdminDialog>
</template>
