<script setup lang="ts">
import { kindEffect, kindLabel, reasonCodes, reasonLabel, sanctionKinds } from '~/utils/sanctions'
import { draftMinutes, draftProblem, durationAllowed, durationPresets, maxMinutesFor, type SanctionDraft, type StaffLimits } from '~/utils/team'

// Eingabe einer Strafe: Art, Dauer (Vorlagen oder eigene), Grund-Vorlage
// (Pflicht), öffentlicher Text, interne Notiz. Was die eigene Rolle nicht darf
// (Moderator: ≤ 7 Tage, Verwarnung ≤ 30, nie dauerhaft, kein Konto-Bann), ist
// ausgegraut – der Server prüft zusätzlich.
const props = defineProps<{ limits: StaffLimits; compact?: boolean }>()
const draft = defineModel<SanctionDraft>({ required: true })

const problem = computed(() => draftProblem(draft.value, props.limits))
const customError = computed(() => (problem.value === 'custom' ? t('team.form.errors.custom') : null))

// Nicht erlaubte Dauer nach Wechsel der Art automatisch korrigieren.
watch(
  () => draft.value.kind,
  (kind) => {
    if (!durationAllowed(draft.value.duration, kind, props.limits)) draft.value.duration = kind === 'warn' ? '30d' : '1d'
  },
)

const limitHint = computed(() => {
  if (props.limits.permanent) return null
  const days = (maxMinutesFor('chat_mute', props.limits) ?? 0) / 1440
  const warnDays = (maxMinutesFor('warn', props.limits) ?? 0) / 1440
  return t('team.form.limitHint', { days, warnDays })
})
const customMinutes = computed(() => (draft.value.duration === 'custom' ? draftMinutes(draft.value) : null))
</script>

<template>
  <div class="space-y-4" data-testid="sanction-form">
    <fieldset>
      <legend class="label">{{ t('team.form.kind') }}</legend>
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="k in sanctionKinds"
          :key="k"
          type="button"
          class="kind-btn"
          :aria-pressed="draft.kind === k"
          :disabled="!limits.kinds.includes(k)"
          :title="limits.kinds.includes(k) ? kindEffect(k) : t('team.form.notAllowed')"
          :data-kind="k"
          @click="draft.kind = k"
        >
          {{ kindLabel(k) }}
        </button>
      </div>
      <p v-if="!compact" class="mt-2 text-xs leading-snug text-base-400">{{ kindEffect(draft.kind) }}</p>
    </fieldset>

    <fieldset>
      <legend class="label">{{ t('team.form.duration') }}</legend>
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="p in durationPresets"
          :key="p"
          type="button"
          class="kind-btn"
          :aria-pressed="draft.duration === p"
          :disabled="!durationAllowed(p, draft.kind, limits)"
          @click="draft.duration = p"
        >
          {{ t(`team.durations.${p}`) }}
        </button>
      </div>
      <div v-if="draft.duration === 'custom'" class="mt-2 flex items-center gap-2">
        <input v-model.number="draft.customValue" type="number" min="1" class="field w-28" :aria-label="t('team.durations.custom')" />
        <select v-model="draft.customUnit" class="field w-auto">
          <option value="minutes">{{ t('team.form.units.minutes') }}</option>
          <option value="hours">{{ t('team.form.units.hours') }}</option>
          <option value="days">{{ t('team.form.units.days') }}</option>
        </select>
        <span v-if="customMinutes && Number.isFinite(customMinutes)" class="text-xs text-base-400">{{ t('team.form.minutes', { n: formatNumber(customMinutes) }) }}</span>
      </div>
      <p v-if="customError" role="alert" class="mt-1 text-xs text-redstone-300">{{ customError }}</p>
      <p v-if="limitHint" class="mt-2 text-xs text-base-400">{{ limitHint }}</p>
    </fieldset>

    <div :class="compact ? 'space-y-3' : 'grid gap-3 sm:grid-cols-2'">
      <div>
        <label class="label" for="sf-reason-code">{{ t('team.form.reasonCode') }} *</label>
        <select id="sf-reason-code" v-model="draft.reasonCode" class="field" data-testid="sanction-reason-code">
          <option value="" disabled>{{ t('team.form.chooseReason') }}</option>
          <option v-for="r in reasonCodes" :key="r" :value="r">{{ reasonLabel(r) }}</option>
        </select>
      </div>
      <div>
        <label class="label" for="sf-reason">{{ t('team.form.publicReason') }}</label>
        <input id="sf-reason" v-model="draft.reason" class="field" maxlength="500" :placeholder="t('team.form.publicReasonHint')" />
      </div>
    </div>
    <div>
      <label class="label" for="sf-note">{{ t('team.form.note') }}</label>
      <textarea id="sf-note" v-model="draft.note" class="field min-h-16 resize-y" maxlength="2000" :placeholder="t('team.form.noteHint')" />
    </div>
  </div>
</template>

<style scoped>
.kind-btn {
  padding: 0.3rem 0.7rem;
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-900);
  color: var(--color-base-200);
  font-size: 0.75rem;
  font-weight: 600;
  transition: border-color 0.15s, background-color 0.15s, color 0.15s;
}
.kind-btn:not(:disabled):hover {
  border-color: var(--color-base-600);
  color: var(--color-base-50);
}
.kind-btn[aria-pressed='true'] {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-900) 70%, transparent);
  color: var(--color-base-50);
  box-shadow: 0 0 10px -3px var(--color-redstone-500);
}
.kind-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
</style>
