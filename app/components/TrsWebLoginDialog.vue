<script setup lang="ts">
// Anmeldung auf der Website bestätigen: Die Website zeigt einen Code, hier
// wird er eingegeben und nach einer ausdrücklichen Rückfrage an die TRS API
// geschickt. Den Aufruf macht der Kern mit dem TRS-Token des aktiven
// Accounts – das Webview sieht den Token nie.
const trs = useTrsStore()
const accounts = useAccountsStore()
const toasts = useToasts()

type Step = 'code' | 'confirm'
const step = ref<Step>('code')
const code = ref('')
/** Normalisierter Code (`ABCD-1234`), sobald er das Format hat. */
const normalized = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

const name = computed(() => trs.me?.name ?? accounts.active?.name ?? '')
/** Ohne Einwilligung oder Account kann nichts bestätigt werden. */
const blocker = computed(() => {
  if (!trs.enabled) return t('webLogin.needsServices')
  if (!accounts.active) return t('webLogin.needsAccount')
  return null
})

function close() {
  if (!busy.value) trs.webLoginOpen = false
}

function next() {
  if (blocker.value) return
  const parsed = trsWebLoginCodeSchema.safeParse(code.value)
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  error.value = null
  normalized.value = parsed.data
  step.value = 'confirm'
}

async function approve() {
  if (busy.value || blocker.value) return
  busy.value = true
  try {
    await backend.trs.webLoginApprove(normalized.value)
    toasts.ok(t('webLogin.done', { host: TRS_HOST }))
    trs.webLoginOpen = false
  } catch (e) {
    // Zurück zur Eingabe – der Code steht noch da und lässt sich korrigieren.
    error.value = errorMessage(e)
    step.value = 'code'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  if (!trs.status) await trs.refreshStatus()
  if (trs.enabled && !trs.me) void trs.loadMe()
})
</script>

<template>
  <BaseDialog :title="t('webLogin.title')" @close="close">
    <p v-if="blocker" class="text-sm text-base-200" role="alert">{{ blocker }}</p>

    <template v-else-if="step === 'code'">
      <p class="mb-3 text-sm text-base-200">
        <i18n-t keypath="webLogin.intro" tag="span" scope="global">
          <template #host><span class="font-mono text-xs text-base-50">{{ TRS_HOST }}</span></template>
        </i18n-t>
      </p>
      <label class="label" for="trs-web-login-code">{{ t('webLogin.codeLabel') }}</label>
      <input
        id="trs-web-login-code"
        v-model="code"
        class="field font-mono tracking-widest uppercase"
        maxlength="32"
        placeholder="ABCD-1234"
        autocomplete="off"
        spellcheck="false"
        autofocus
        data-testid="web-login-code"
        @keydown.enter="next"
      />
      <p v-if="error" role="alert" class="mt-2 text-xs text-redstone-300">{{ error }}</p>
    </template>

    <template v-else>
      <i18n-t keypath="webLogin.question" tag="p" scope="global" class="text-sm text-base-100">
        <template #name><strong class="text-base-50">{{ name }}</strong></template>
        <template #host><span class="font-mono text-xs text-base-50">{{ TRS_HOST }}</span></template>
      </i18n-t>
      <p class="mt-1 text-xs text-base-400">
        {{ t('webLogin.codeShown') }} <code class="font-mono tracking-wider text-base-200">{{ normalized }}</code>
      </p>
      <div class="mt-3 rounded-lg border border-redstone-600/50 bg-redstone-900/30 px-3 py-2.5 text-xs text-redstone-300" role="alert">
        <p class="font-semibold">{{ t('webLogin.warningTitle') }}</p>
        <p class="mt-0.5">{{ t('webLogin.warning') }}</p>
      </div>
    </template>

    <template #actions>
      <template v-if="blocker">
        <button class="btn btn-ghost" @click="close">{{ t('common.actions.close') }}</button>
      </template>
      <template v-else-if="step === 'code'">
        <button class="btn btn-ghost" @click="close">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" @click="next">{{ t('common.actions.continue') }}</button>
      </template>
      <template v-else>
        <button class="btn btn-ghost" :disabled="busy" @click="step = 'code'">{{ t('common.actions.back') }}</button>
        <button class="btn btn-primary" :disabled="busy" data-testid="web-login-approve" @click="approve">
          {{ busy ? t('webLogin.approving') : t('webLogin.approve') }}
        </button>
      </template>
    </template>
  </BaseDialog>
</template>
