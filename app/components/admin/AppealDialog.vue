<script setup lang="ts">
import { kindLabel, reasonLabel } from '~/utils/sanctions'
import type { AdminAppeal } from '~/utils/team'

// Über einen Einspruch entscheiden: aufheben, verkürzen (neues Ende) oder
// bestätigen – mit Antwort an den Spieler (1–1000 Zeichen).
const props = defineProps<{ appeal: AdminAppeal; decision?: 'lift' | 'shorten' | 'uphold' }>()
const emit = defineEmits<{ close: []; decided: [appeal: AdminAppeal] }>()

const decision = ref<'lift' | 'shorten' | 'uphold'>(props.decision ?? 'uphold')
const response = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const s = computed(() => props.appeal.sanction)

function toLocal(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
/** Vorschlag fürs Verkürzen: halbe Restzeit, mindestens 1 Stunde ab jetzt. */
function suggestion(): string {
  const end = s.value.endsAt ? Date.parse(s.value.endsAt) : Date.now() + 14 * 86_400_000
  const half = Date.now() + Math.max(3_600_000, (end - Date.now()) / 2)
  return toLocal(new Date(half))
}
const endLocal = ref(suggestion())
const length = computed(() => [...response.value.trim()].length)

async function submit() {
  if (!length.value || length.value > 1000) {
    error.value = t('team.appeals.responseRequired')
    return
  }
  busy.value = true
  error.value = null
  try {
    const input = { decision: decision.value, response: response.value.trim(), ...(decision.value === 'shorten' ? { endsAt: new Date(endLocal.value).toISOString() } : {}) }
    const { appeal } = await backend.team.decideAppeal(props.appeal.id, input)
    emit('decided', appeal)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="t('team.appeals.decideTitle', { name: s.player.name ?? s.player.uuid })" wide @close="emit('close')">
    <div class="space-y-4 text-sm" data-testid="appeal-dialog">
      <div class="rounded-lg border border-base-800 bg-base-900 p-3">
        <p class="flex flex-wrap items-center gap-2">
          <SanctionKindBadge :kind="s.kind" />
          <span class="text-base-200">{{ reasonLabel(s.reasonCode) }}</span>
          <span class="text-xs text-base-400">· {{ s.endsAt ? t('team.sanction.until', { date: formatDate(s.endsAt) }) : t('team.durations.permanent') }}</span>
        </p>
        <p v-if="s.reason" class="mt-1 text-xs text-base-400">„{{ s.reason }}“</p>
        <p v-if="s.note" class="mt-1 text-xs text-lamp-300">{{ t('team.form.note') }}: {{ s.note }}</p>
      </div>
      <div>
        <p class="label">{{ t('team.appeals.text', { date: formatDate(appeal.createdAt) }) }}</p>
        <blockquote class="rounded-lg border-l-2 border-redstone-500 bg-base-850 px-3 py-2 whitespace-pre-wrap text-base-50">{{ appeal.text }}</blockquote>
      </div>
      <fieldset>
        <legend class="label">{{ t('team.appeals.decision') }}</legend>
        <div class="grid gap-2 sm:grid-cols-3">
          <label v-for="d in (['lift', 'shorten', 'uphold'] as const)" :key="d" class="decision" :class="{ 'decision-on': decision === d }">
            <input v-model="decision" type="radio" :value="d" class="sr-only" />
            <span class="font-semibold text-base-50">{{ t(`team.appeals.decisions.${d}`) }}</span>
            <span class="text-[11px] text-base-400">{{ t(`team.appeals.decisionHelp.${d}`, { kind: kindLabel(s.kind) }) }}</span>
          </label>
        </div>
        <div v-if="decision === 'shorten'" class="mt-3">
          <label class="label" for="appeal-end">{{ t('team.change.newEnd') }}</label>
          <input id="appeal-end" v-model="endLocal" type="datetime-local" class="field" />
        </div>
      </fieldset>
      <div>
        <label class="label" for="appeal-response">{{ t('team.appeals.response') }} *</label>
        <textarea id="appeal-response" v-model="response" class="field min-h-24 resize-y" maxlength="1050" :placeholder="t('team.appeals.responseHint')" data-testid="appeal-response" />
        <p class="mt-1 text-right text-[11px] tabular-nums" :class="length > 1000 ? 'text-redstone-300' : 'text-base-400'">{{ length }} / 1000</p>
      </div>
      <p v-if="error" role="alert" class="text-xs text-redstone-300">{{ error }}</p>
    </div>
    <template #actions>
      <button class="btn btn-ghost" :disabled="busy" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="busy || !length" data-testid="appeal-decide" @click="submit">
        {{ busy ? t('team.common.working') : t('team.appeals.send') }}
      </button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.decision {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  cursor: pointer;
  border-radius: 0.6rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-900);
  padding: 0.6rem 0.75rem;
  transition: border-color 0.15s, background-color 0.15s;
}
.decision:hover {
  border-color: var(--color-base-600);
}
.decision-on {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-900) 55%, transparent);
}
</style>
