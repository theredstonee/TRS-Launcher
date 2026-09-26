<script setup lang="ts">
import { kindEffect, kindLabel, reasonLabel } from '~/utils/sanctions'
import { draftEnd, draftProblem, draftToInput, emptyDraft, type AdminSanction, type SanctionDraft } from '~/utils/team'

// Neue Strafe für einen Spieler: erst Formular, dann Bestätigung mit allem,
// was passiert (Art, Ende, was der Spieler sieht). Erst danach geht sie raus.
const props = defineProps<{ player: { uuid: string; name: string | null }; reportId?: string }>()
const emit = defineEmits<{ close: []; created: [sanction: AdminSanction] }>()
const team = useTeam()

const draft = ref<SanctionDraft>(emptyDraft('warn'))
const step = ref<'form' | 'confirm'>('form')
const busy = ref(false)
const error = ref<string | null>(null)
const problem = computed(() => draftProblem(draft.value, team.limits.value))
const end = computed(() => draftEnd(draft.value))
const name = computed(() => props.player.name || props.player.uuid)

function next() {
  error.value = problem.value ? t(`team.form.errors.${problem.value}`) : null
  if (!problem.value) step.value = 'confirm'
}

async function submit() {
  busy.value = true
  error.value = null
  try {
    const { sanction } = await backend.team.createSanction(draftToInput(props.player.uuid, draft.value, props.reportId))
    emit('created', sanction)
  } catch (e) {
    error.value = errorMessage(e)
    step.value = 'form'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="t('team.sanction.newTitle', { name })" wide @close="emit('close')">
    <div data-testid="sanction-dialog">
      <AdminSanctionForm v-if="step === 'form'" v-model="draft" :limits="team.limits.value" />
      <div v-else class="space-y-3 text-sm" data-testid="sanction-confirm">
        <p class="text-base-200">{{ t('team.sanction.confirmLead', { name }) }}</p>
        <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-lg border border-base-800 bg-base-900 p-4">
          <dt class="text-base-400">{{ t('team.form.kind') }}</dt>
          <dd class="flex flex-wrap items-center gap-2"><SanctionKindBadge :kind="draft.kind" /><span class="text-xs text-base-400">{{ kindEffect(draft.kind) }}</span></dd>
          <dt class="text-base-400">{{ t('team.form.duration') }}</dt>
          <dd class="text-base-50">{{ end ? t('team.sanction.until', { date: formatDate(end.toISOString()) }) : t('team.durations.permanent') }}</dd>
          <dt class="text-base-400">{{ t('team.form.reasonCode') }}</dt>
          <dd class="text-base-50">{{ reasonLabel(draft.reasonCode) }}</dd>
          <dt class="text-base-400">{{ t('team.form.publicReason') }}</dt>
          <dd class="text-base-50">{{ draft.reason.trim() || '–' }}</dd>
          <dt class="text-base-400">{{ t('team.form.note') }}</dt>
          <dd class="whitespace-pre-wrap text-base-200">{{ draft.note.trim() || '–' }}</dd>
        </dl>
        <p class="text-xs text-base-400">{{ t('team.sanction.playerSees', { kind: kindLabel(draft.kind) }) }}</p>
        <p v-if="draft.kind === 'account_ban'" class="rounded-md bg-redstone-900 px-3 py-2 text-xs text-redstone-300">{{ t('team.sanction.banWarning') }}</p>
      </div>
      <p v-if="error" role="alert" class="mt-3 text-xs text-redstone-300">{{ error }}</p>
    </div>
    <template #actions>
      <button v-if="step === 'confirm'" class="btn btn-ghost" :disabled="busy" @click="step = 'form'">{{ t('team.common.back') }}</button>
      <button v-else class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button v-if="step === 'form'" class="btn btn-primary" data-testid="sanction-next" @click="next">{{ t('team.sanction.review') }}</button>
      <button v-else :class="draft.kind === 'account_ban' ? 'btn btn-danger' : 'btn btn-primary'" :disabled="busy" data-testid="sanction-submit" @click="submit">
        {{ busy ? t('team.common.working') : t('team.sanction.submit') }}
      </button>
    </template>
  </BaseDialog>
</template>
