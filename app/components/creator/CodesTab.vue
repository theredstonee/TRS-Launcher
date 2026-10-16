<script setup lang="ts">
import {
  canAddCode,
  cleanCodeInput,
  codeStatus,
  expiryFromDate,
  extraCodeCount,
  normalizeCreatorCode,
  tierAtLeast,
  type CreatorCode,
  type CreatorSelf,
} from '~/utils/creator'

// Codes: Haupt-Code (ändert nur das Team) und Zusatz-Codes mit Grenze und
// Ablauf – Anzahl und Höchstzahl je Stufe. Helfer mit Recht „codes“ dürfen
// Zusatz-Codes anlegen und schalten (§35.9).
const props = defineProps<{ self: CreatorSelf }>()
const store = useCreatorStore()
const toasts = useToasts()

const extra = computed(() => extraCodeCount(props.self.codes))
const limit = computed(() => props.self.limits.extraCodes)
const cap = computed(() => props.self.limits.maxUsesPerCode)
const canAdd = computed(() => canAddCode(props.self.codes, props.self.limits))
const partner = computed(() => tierAtLeast(props.self.tier, 'partner'))

function setCodes(codes: CreatorCode[]) {
  if (store.dashboard) store.dashboard = { ...store.dashboard, codes }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toasts.ok(t('creator.copied', { code: text }))
  } catch {
    toasts.error(t('admin.toasts.copyFailed'))
  }
}

const toggling = ref<string | null>(null)
async function toggle(c: CreatorCode) {
  toggling.value = c.code
  try {
    setCodes(await backend.creator.updateCode(c.code, { active: !c.active }, store.actingAs))
  } catch (e) {
    toasts.error(e)
  } finally {
    toggling.value = null
  }
}

// --- Neuer Code -------------------------------------------------------------------------
const dialog = ref(false)
const form = reactive({ code: '', maxUses: 100 as number | '', until: '' })
const formError = ref<string | null>(null)
const saving = ref(false)

function openDialog() {
  const main = props.self.codes.find((c) => c.kind === 'main')?.code ?? ''
  form.code = cleanCodeInput(`${main.replace(/^TRS-/, '')}-`)
  form.maxUses = cap.value === null ? '' : Math.min(100, cap.value)
  form.until = ''
  formError.value = null
  dialog.value = true
}

async function create() {
  const code = normalizeCreatorCode(form.code)
  if (!code) {
    formError.value = t('creator.codes.invalid')
    return
  }
  const max = form.maxUses === '' ? null : Number(form.maxUses)
  if (max !== null && (!Number.isInteger(max) || max < 1 || (cap.value !== null && max > cap.value))) {
    formError.value = t('creator.codes.maxInvalid', { max: formatNumber(cap.value ?? 0) })
    return
  }
  if (cap.value !== null && max === null) {
    formError.value = t('creator.codes.maxRequired', { max: formatNumber(cap.value) })
    return
  }
  const expiresAt = form.until ? expiryFromDate(form.until) : null
  if (form.until && (!expiresAt || Date.parse(expiresAt) <= Date.now())) {
    formError.value = t('creator.codes.untilInvalid')
    return
  }
  formError.value = null
  saving.value = true
  try {
    setCodes(await backend.creator.createCode({ code, ...(max !== null ? { maxUses: max } : {}), ...(expiresAt ? { expiresAt } : {}) }, store.actingAs))
    dialog.value = false
    toasts.ok(t('creator.codes.created', { code }))
  } catch (e) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

const statusClass = { active: 'bg-ok/15 text-ok', off: 'bg-base-800 text-base-400', usedUp: 'bg-base-800 text-base-400', expired: 'bg-base-800 text-base-400' } as const
</script>

<template>
  <div class="space-y-3" data-testid="creator-codes">
    <p class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-400">
      {{ partner ? t('creator.codes.notePartner') : t('creator.codes.note') }}
    </p>
    <section class="card p-4">
      <div class="flex flex-wrap items-center gap-2">
        <h3 class="section-title mr-auto">{{ t('creator.codes.title') }}</h3>
        <span class="text-xs text-base-400">{{ t('creator.codes.extraCount', { n: extra, max: limit === null ? '∞' : limit }) }}</span>
        <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!canAdd" data-testid="creator-new-code" @click="openDialog">
          <SocialIcon name="plus" class="size-4" />{{ t('creator.codes.new') }}
        </button>
      </div>
      <div class="mt-3 overflow-x-auto">
        <table class="w-full min-w-[34rem] text-sm">
          <thead>
            <tr class="text-left text-[11px] tracking-wide text-base-400 uppercase">
              <th class="py-1.5 font-medium">{{ t('creator.codes.code') }}</th>
              <th class="py-1.5 font-medium">{{ t('creator.codes.kind') }}</th>
              <th class="py-1.5 font-medium">{{ t('creator.codes.uses') }}</th>
              <th class="py-1.5 font-medium">{{ t('creator.codes.until') }}</th>
              <th class="py-1.5 font-medium">{{ t('creator.codes.status') }}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in self.codes" :key="c.code" class="border-t border-base-800">
              <td class="py-2 font-mono text-xs font-semibold text-redstone-300">{{ c.code }}</td>
              <td class="py-2 text-xs text-base-200">{{ c.kind === 'main' ? t('creator.codes.main') : t('creator.codes.extra') }}</td>
              <td class="py-2 text-xs tabular-nums">{{ formatNumber(c.uses) }}<template v-if="c.maxUses !== null"> / {{ formatNumber(c.maxUses) }}</template></td>
              <td class="py-2 text-xs text-base-400">{{ c.expiresAt ? formatDate(c.expiresAt) : '–' }}</td>
              <td class="py-2">
                <span class="badge" :class="statusClass[codeStatus(c)]">{{ t(`creator.codes.state.${codeStatus(c)}`) }}</span>
              </td>
              <td class="py-2 text-right whitespace-nowrap">
                <button class="btn-icon size-8" :aria-label="t('creator.copy')" :title="t('creator.copy')" @click="copy(c.code)"><SocialIcon name="copy" class="size-4" /></button>
                <button v-if="c.kind === 'extra'" class="btn btn-ghost ml-1 px-3 py-1 text-xs" :disabled="toggling === c.code" @click="toggle(c)">
                  {{ c.active ? t('creator.codes.turnOff') : t('creator.codes.turnOn') }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="mt-3 text-xs text-base-400">
        {{ cap === null ? t('creator.codes.footerUnlimited') : t('creator.codes.footer', { max: formatNumber(cap) }) }}
      </p>
    </section>

    <BaseDialog v-if="dialog" :title="t('creator.codes.newTitle')" @close="dialog = false">
      <form class="space-y-3" @submit.prevent="create">
        <label class="block">
          <span class="label">{{ t('creator.codes.codeLabel') }}</span>
          <input
            :value="form.code"
            class="field font-mono font-bold tracking-widest uppercase"
            maxlength="20"
            autocomplete="off"
            spellcheck="false"
            data-testid="creator-code-new"
            @input="form.code = cleanCodeInput(($event.target as HTMLInputElement).value)"
          />
        </label>
        <label class="block">
          <span class="label">{{ cap === null ? t('creator.codes.maxLabelOptional') : t('creator.codes.maxLabel', { max: formatNumber(cap) }) }}</span>
          <input v-model.number="form.maxUses" type="number" min="1" :max="cap ?? undefined" class="field" />
        </label>
        <label class="block">
          <span class="label">{{ t('creator.codes.untilLabel') }}</span>
          <input v-model="form.until" type="date" class="field" />
        </label>
        <p v-if="formError" role="alert" class="text-xs text-redstone-300">{{ formError }}</p>
      </form>
      <template #actions>
        <button class="btn btn-ghost" @click="dialog = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="saving" @click="create">{{ saving ? t('creator.codes.creating') : t('creator.codes.create') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
