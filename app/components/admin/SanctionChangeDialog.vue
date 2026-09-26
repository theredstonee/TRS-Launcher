<script setup lang="ts">
import { kindLabel } from '~/utils/sanctions'
import { maxMinutesFor, type AdminSanction } from '~/utils/team'

// Strafe aufheben oder das Ende ändern (verkürzen/verlängern) – immer mit
// Begründung. Moderatoren: Grenzen ab Beginn der Strafe (≤ 7 Tage, Verwarnung
// ≤ 30), nie dauerhaft; der Server prüft das.
const props = defineProps<{ sanction: AdminSanction; mode: 'lift' | 'change' }>()
const emit = defineEmits<{ close: []; changed: [sanction: AdminSanction] }>()
const team = useTeam()

const reason = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const permanent = ref(false)
/** `datetime-local` (Ortszeit). */
const endLocal = ref(toLocal(props.sanction.endsAt ? new Date(props.sanction.endsAt) : new Date(Date.now() + 86_400_000)))

function toLocal(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const newEnd = computed(() => (permanent.value ? null : new Date(endLocal.value)))
const oldEnd = computed(() => (props.sanction.endsAt ? new Date(props.sanction.endsAt) : null))
const direction = computed<'shorten' | 'extend' | 'same' | null>(() => {
  const n = newEnd.value
  const o = oldEnd.value
  if (n && Number.isNaN(n.getTime())) return null
  if (!n && !o) return 'same'
  if (!n) return 'extend'
  if (!o) return 'shorten'
  const diff = n.getTime() - o.getTime()
  return Math.abs(diff) < 60_000 ? 'same' : diff < 0 ? 'shorten' : 'extend'
})
const tooLong = computed(() => {
  const max = maxMinutesFor(props.sanction.kind, team.limits.value)
  if (max === null) return false
  if (!newEnd.value) return true
  return newEnd.value.getTime() - Date.parse(props.sanction.createdAt) > max * 60_000 + 60_000
})

/** Schnellwahl: ab jetzt. */
function setIn(hours: number) {
  permanent.value = false
  endLocal.value = toLocal(new Date(Date.now() + hours * 3_600_000))
}

async function submit() {
  if (!reason.value.trim()) {
    error.value = t('team.change.reasonRequired')
    return
  }
  busy.value = true
  error.value = null
  try {
    const result =
      props.mode === 'lift'
        ? await backend.team.liftSanction(props.sanction.id, reason.value.trim())
        : await backend.team.changeSanction(props.sanction.id, newEnd.value ? newEnd.value.toISOString() : null, reason.value.trim())
    emit('changed', result.sanction)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="mode === 'lift' ? t('team.change.liftTitle', { kind: kindLabel(sanction.kind) }) : t('team.change.changeTitle', { kind: kindLabel(sanction.kind) })" @close="emit('close')">
    <div class="space-y-3 text-sm" data-testid="sanction-change">
      <p class="text-base-200">
        {{ t('team.change.current', { name: sanction.player.name ?? sanction.player.uuid, end: sanction.endsAt ? formatDate(sanction.endsAt) : t('team.durations.permanent') }) }}
      </p>
      <template v-if="mode === 'change'">
        <div class="flex flex-wrap gap-1.5">
          <button v-for="h in [1, 24, 72, 168]" :key="h" type="button" class="chip hover:bg-base-700" @click="setIn(h)">{{ t('team.change.inHours', { n: h }) }}</button>
          <button v-if="team.limits.value.permanent" type="button" class="chip hover:bg-base-700" :class="{ 'ring-1 ring-redstone-500': permanent }" @click="permanent = !permanent">
            {{ t('team.durations.permanent') }}
          </button>
        </div>
        <div v-if="!permanent">
          <label class="label" for="change-end">{{ t('team.change.newEnd') }}</label>
          <input id="change-end" v-model="endLocal" type="datetime-local" class="field" />
        </div>
        <p v-if="direction && direction !== 'same'" class="text-xs" :class="direction === 'shorten' ? 'text-ok' : 'text-lamp-300'">
          {{ t(`team.change.${direction}`) }}
        </p>
        <p v-if="direction === 'same'" class="text-xs text-base-400">{{ t('team.change.same') }}</p>
        <p v-if="tooLong" class="text-xs text-lamp-300">{{ t('team.change.tooLong') }}</p>
      </template>
      <div>
        <label class="label" for="change-reason">{{ t('team.change.reason') }} *</label>
        <input id="change-reason" v-model="reason" class="field" maxlength="500" :placeholder="t('team.change.reasonHint')" data-testid="change-reason" @keydown.enter.prevent="submit" />
      </div>
      <p v-if="error" role="alert" class="text-xs text-redstone-300">{{ error }}</p>
    </div>
    <template #actions>
      <button class="btn btn-ghost" :disabled="busy" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button
        class="btn btn-primary"
        :disabled="busy || !reason.trim() || (mode === 'change' && (direction === 'same' || direction === null))"
        data-testid="change-submit"
        @click="submit"
      >
        {{ busy ? t('team.common.working') : mode === 'lift' ? t('team.change.lift') : t('team.change.save') }}
      </button>
    </template>
  </BaseDialog>
</template>
