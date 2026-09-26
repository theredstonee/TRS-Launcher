<script setup lang="ts">
// Eingabe einer Strafe: Art, Dauer (Vorlagen oder eigene), Grund-Vorlage (Pflicht), öffentlicher
// Freitext, interne Notiz. Grenzen der Rolle (Moderator: ≤ 7 Tage, Verwarnung ≤ 30, nie dauerhaft,
// kein Konto-Bann) sind ausgegraut – geprüft wird zusätzlich auf dem Server.
const props = defineProps<{ limits: { kinds: string[], maxMinutes: number | null, maxWarnMinutes: number | null, permanent: boolean } }>()
const draft = defineModel<SanctionDraft>({ required: true })
const { a, fill } = useAdminText()

const UNIT = { minutes: 1, hours: 60, days: 1440 } as const

function maxFor(kind: SanctionKind): number | null {
  return kind === 'warn' ? props.limits.maxWarnMinutes : props.limits.maxMinutes
}
function kindAllowed(kind: SanctionKind): boolean {
  return props.limits.kinds.includes(kind)
}
function durationAllowed(p: DurationPreset): boolean {
  if (p === 'permanent') return props.limits.permanent
  if (p === 'custom') return true
  const max = maxFor(draft.value.kind)
  return max === null || DURATION_MINUTES[p]! <= max
}
/** Minuten der Auswahl (`null` = dauerhaft, `NaN` = ungültig). */
const minutes = computed<number | null>(() => {
  const d = draft.value
  if (d.duration === 'permanent') return null
  if (d.duration === 'custom') return Math.round(d.customValue * UNIT[d.customUnit])
  return DURATION_MINUTES[d.duration]!
})
const customError = computed(() => {
  if (draft.value.duration !== 'custom') return ''
  const m = minutes.value
  if (m === null || !Number.isFinite(m) || m < 5) return '≥ 5 min'
  const max = maxFor(draft.value.kind)
  if (max !== null && m > max) return a.value.decision.notAllowed
  return ''
})
const valid = computed(() => kindAllowed(draft.value.kind) && durationAllowed(draft.value.duration) && !customError.value && !!draft.value.reasonCode)
defineExpose({ valid, minutes })

// Nicht erlaubte Auswahl nach Wechsel der Art automatisch korrigieren.
watch(() => draft.value.kind, () => {
  if (!durationAllowed(draft.value.duration)) draft.value.duration = '1d'
})

const maxDays = computed(() => ((props.limits.maxMinutes ?? 0) / 1440))
const maxWarnDays = computed(() => ((props.limits.maxWarnMinutes ?? 0) / 1440))
</script>

<template>
  <div class="space-y-5">
    <fieldset>
      <legend class="label">{{ a.decision.kind }}</legend>
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="k in SANCTION_KINDS"
          :key="k"
          type="button"
          class="kind-btn"
          :class="draft.kind === k ? kindTone(k) : ''"
          :aria-pressed="draft.kind === k"
          :disabled="!kindAllowed(k)"
          :title="kindAllowed(k) ? a.kindHelp[k] : a.decision.notAllowed"
          @click="draft.kind = k"
        >
          {{ a.kinds[k] }}
        </button>
      </div>
      <p class="mt-2 text-xs leading-snug text-base-400">{{ a.kindHelp[draft.kind] }}</p>
    </fieldset>

    <fieldset>
      <legend class="label">{{ a.decision.duration }}</legend>
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="p in DURATION_PRESETS"
          :key="p"
          type="button"
          class="adm-chip-btn"
          :aria-pressed="draft.duration === p"
          :disabled="!durationAllowed(p)"
          @click="draft.duration = p"
        >
          {{ a.durations[p] }}
        </button>
      </div>
      <div v-if="draft.duration === 'custom'" class="mt-2 flex gap-2">
        <input v-model.number="draft.customValue" type="number" min="1" class="field w-28" :aria-label="a.durations.custom" />
        <select v-model="draft.customUnit" class="field w-auto">
          <option value="minutes">{{ a.customUnit.minutes }}</option>
          <option value="hours">{{ a.customUnit.hours }}</option>
          <option value="days">{{ a.customUnit.days }}</option>
        </select>
      </div>
      <p v-if="customError" class="mt-1 text-xs text-redstone-300">{{ customError }}</p>
      <p v-if="!limits.permanent" class="mt-2 text-xs text-base-400">{{ fill(a.common.limitHint, { days: maxDays, warnDays: maxWarnDays }) }}</p>
    </fieldset>

    <div>
      <label class="label" for="sf-reason-code">{{ a.decision.reasonCode }} *</label>
      <select id="sf-reason-code" v-model="draft.reasonCode" class="field" required>
        <option value="" disabled>–</option>
        <option v-for="r in REASON_CODES" :key="r" :value="r">{{ a.reasons[r] }}</option>
      </select>
    </div>
    <div>
      <label class="label" for="sf-reason">{{ a.decision.publicReason }}</label>
      <input id="sf-reason" v-model="draft.reason" class="field" maxlength="500" />
    </div>
    <div>
      <label class="label" for="sf-note">{{ a.decision.note }}</label>
      <textarea id="sf-note" v-model="draft.note" class="field min-h-20" maxlength="2000" />
    </div>
  </div>
</template>

<style scoped>
.kind-btn {
  padding: 0.35rem 0.75rem;
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  font-size: 0.8125rem;
  font-weight: 600;
}
.kind-btn:not([aria-pressed="true"]) {
  color: var(--color-base-200);
  background: var(--color-base-900);
}
.kind-btn[aria-pressed="true"] {
  border-color: currentColor;
}
.kind-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
