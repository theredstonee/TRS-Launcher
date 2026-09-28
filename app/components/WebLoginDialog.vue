<script setup lang="ts">
import { WEBSITE_LOGIN_URL } from '~/utils/teamAccess'
import type { WebLoginAccount, WebLoginRequest } from '~/utils/webLogin'

// „Auf der Website anmelden“ (API §29): Die Website zeigt einen Code; hier stehen derselbe Code, die Website, der
// Browser und die Uhrzeit der Anfrage und das Konto, mit dem angemeldet wird (aktives vorausgewählt, andere Konten
// mit TRS-Anmeldung wählbar). Bestätigt wird NUR mit einem Klick auf „Bestätigen“ – nie automatisch, auch nicht per
// Enter. Ohne Token (anderer PC) tippt man den Code von der Website ein.
const props = defineProps<{ token: string | null }>()
const emit = defineEmits<{ close: [] }>()

const trs = useTrsStore()
const toasts = useToasts()

type Step = 'code' | 'loading' | 'confirm' | 'sending' | 'done' | 'denied' | 'error'
const step = ref<Step>(props.token ? 'loading' : 'code')
const codeInput = ref('')
const request = ref<WebLoginRequest | null>(null)
const accounts = ref<WebLoginAccount[]>([])
const account = ref<string | null>(null)
const error = ref<string | null>(null)
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

const normalized = computed(() => normalizeWebLoginCode(codeInput.value))
// Neue Eingabe → alter Fehler weg.
watch(codeInput, () => {
  if (step.value === 'code') error.value = null
})
const left = computed(() => (request.value ? secondsLeft(request.value.expiresAt, now.value) : 0))
const leftText = computed(() => `${Math.floor(left.value / 60)}:${String(left.value % 60).padStart(2, '0')}`)
const chosen = computed(() => accounts.value.find((a) => a.id === account.value) ?? null)
const createdTime = computed(() => {
  const t = request.value?.createdAt ? new Date(request.value.createdAt) : null
  return t && !Number.isNaN(t.getTime()) ? t.toLocaleTimeString(currentLocale.value, { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : ''
})

async function loadAccounts() {
  accounts.value = await backend.webLogin.accounts()
  if (!accounts.value.some((a) => a.id === account.value)) account.value = accounts.value.find((a) => a.active)?.id ?? accounts.value[0]?.id ?? null
}

async function lookup(source: { token: string } | { code: string }) {
  step.value = 'loading'
  error.value = null
  try {
    await loadAccounts()
    request.value = await backend.webLogin.lookup(account.value, source)
    step.value = 'confirm'
  } catch (e) {
    error.value = errorMessage(e)
    step.value = 'token' in source ? 'error' : 'code'
  }
}

function submitCode() {
  if (!normalized.value) {
    error.value = t('webLogin.invalidCode')
    return
  }
  void lookup({ code: normalized.value })
}

async function decide(approve: boolean) {
  const r = request.value
  if (!r || step.value !== 'confirm') return
  step.value = 'sending'
  error.value = null
  try {
    await backend.webLogin.decide(account.value, r.id, r.code, approve)
    step.value = approve ? 'done' : 'denied'
    if (approve) toasts.ok(t('webLogin.doneToast', { site: r.site }))
  } catch (e) {
    error.value = errorMessage(e)
    step.value = 'error'
  }
}

function openWebsite() {
  backend.openExternalUrl(WEBSITE_LOGIN_URL).catch((e) => toasts.error(e))
}

/** Nachschlagen per Link erst, wenn die TRS-Dienste sicher an sind (beim Start per Link lädt der Status noch). */
let started = false
function start() {
  if (started || !trs.enabled || !props.token) return
  started = true
  void lookup({ token: props.token })
}
watch(() => trs.enabled, start)

onMounted(() => {
  if (!trs.status) void trs.refreshStatus()
  timer = setInterval(() => {
    now.value = Date.now()
    // Abgelaufen: nicht mehr bestätigen lassen.
    if (step.value === 'confirm' && request.value?.expiresAt && left.value === 0) {
      error.value = t('errors.trsWebLogin.expired')
      step.value = 'error'
    }
  }, 500)
  start()
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <BaseDialog :title="t('webLogin.title')" wide @close="emit('close')">
    <!-- TRS-Dienste aus: ohne Einwilligung geht keine Anfrage raus -->
    <div v-if="trs.status && !trs.enabled" class="space-y-3 text-sm">
      <p class="text-base-200">{{ t('webLogin.needsServices') }}</p>
      <button class="btn btn-primary" @click="trs.askConsent()">{{ t('webLogin.enableServices') }}</button>
    </div>

    <!-- Code von Hand (anderer PC / Link hat nicht geöffnet) -->
    <form v-else-if="step === 'code' && trs.enabled" class="space-y-3" @submit.prevent="submitCode">
      <p class="text-sm text-base-200">{{ t('webLogin.codeIntro', { host: TRS_HOST }) }}</p>
      <label class="block">
        <span class="mb-1 block text-xs text-base-400">{{ t('webLogin.codeLabel') }}</span>
        <input
          v-model="codeInput"
          class="field w-full text-center font-mono text-2xl tracking-[0.3em] uppercase"
          maxlength="9"
          autocomplete="off"
          spellcheck="false"
          placeholder="XXX-XXX"
          data-testid="web-login-code-input"
          autofocus
        />
      </label>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
      <button type="button" class="text-xs text-redstone-300 hover:underline" @click="openWebsite">{{ t('webLogin.openWebsite', { host: TRS_HOST }) }}</button>
    </form>

    <div v-else-if="step === 'loading' || !trs.status" class="space-y-2 py-2">
      <div class="skeleton h-5 w-2/3" />
      <div class="skeleton h-16 w-full" />
      <div class="skeleton h-10 w-full" />
    </div>

    <!-- Bestätigen -->
    <div v-else-if="(step === 'confirm' || step === 'sending') && request" class="space-y-4" data-testid="web-login-confirm">
      <p class="text-sm text-base-200">{{ t('webLogin.question', { site: request.site }) }}</p>

      <div class="code-box">
        <p class="text-[11px] tracking-wide text-base-400 uppercase">{{ t('webLogin.codeHeading') }}</p>
        <p class="font-mono text-4xl font-bold tracking-[0.2em] text-base-50 select-all" data-testid="web-login-code">{{ request.code }}</p>
        <p class="text-xs text-base-400">{{ t('webLogin.compare') }}</p>
      </div>

      <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
        <dt class="text-base-400">{{ t('webLogin.site') }}</dt>
        <dd class="font-medium text-base-50">{{ request.site }}</dd>
        <dt class="text-base-400">{{ t('webLogin.browser') }}</dt>
        <dd class="text-base-200">{{ request.browser ?? t('webLogin.unknownBrowser') }}</dd>
        <dt class="text-base-400">{{ t('webLogin.time') }}</dt>
        <dd class="text-base-200">
          {{ createdTime }}
          <span v-if="request.expiresAt" class="ml-2 text-xs text-base-400">{{ t('webLogin.expires', { time: leftText }) }}</span>
        </dd>
      </dl>

      <fieldset>
        <legend class="mb-1.5 text-xs text-base-400">{{ t('webLogin.account') }}</legend>
        <div class="space-y-1.5" role="radiogroup">
          <label
            v-for="a in accounts"
            :key="a.id"
            class="account-row"
            :class="{ 'is-on': account === a.id }"
            :data-testid="`web-login-account-${a.name}`"
          >
            <input v-model="account" type="radio" name="web-login-account" :value="a.id" class="sr-only" :disabled="step === 'sending'" />
            <SkinHead :skin-url="a.skinUrl" :name="a.name" :size="28" />
            <span class="flex-1 font-medium text-base-50">{{ a.name }}</span>
            <span v-if="a.active" class="badge bg-base-800 text-[10px] text-base-200">{{ t('webLogin.activeAccount') }}</span>
            <span class="dot" aria-hidden="true" />
          </label>
        </div>
        <p v-if="accounts.length > 1" class="mt-1.5 text-[11px] text-base-400">{{ t('webLogin.otherAccounts') }}</p>
      </fieldset>

      <p class="flex gap-2 rounded-md border border-lamp-400/30 bg-lamp-900/30 px-3 py-2 text-xs text-base-200">
        <span aria-hidden="true">⚠</span>
        <span>{{ t('webLogin.warning', { site: request.site }) }}</span>
      </p>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </div>

    <div v-else-if="step === 'done'" class="space-y-2 text-sm" role="status">
      <p class="font-semibold text-ok">{{ t('webLogin.done', { name: chosen?.name ?? '' }) }}</p>
      <p class="text-base-200">{{ t('webLogin.doneHint') }}</p>
    </div>

    <div v-else-if="step === 'denied'" class="space-y-2 text-sm" role="status">
      <p class="font-semibold text-base-50">{{ t('webLogin.denied') }}</p>
      <p class="text-base-200">{{ t('webLogin.deniedHint') }}</p>
    </div>

    <div v-else class="space-y-3 text-sm">
      <p role="alert" class="text-redstone-300">{{ error }}</p>
      <p class="text-base-400">{{ t('webLogin.errorHint') }}</p>
    </div>

    <template #actions>
      <template v-if="trs.enabled && step === 'code'">
        <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!normalized" data-testid="web-login-next" @click="submitCode">{{ t('webLogin.next') }}</button>
      </template>
      <template v-else-if="trs.enabled && (step === 'confirm' || step === 'sending')">
        <button class="btn btn-ghost" :disabled="step === 'sending'" data-testid="web-login-deny" @click="decide(false)">{{ t('webLogin.deny') }}</button>
        <button class="btn btn-primary" :disabled="step === 'sending' || !account" data-testid="web-login-approve" @click="decide(true)">
          {{ step === 'sending' ? t('webLogin.sending') : t('webLogin.approve') }}
        </button>
      </template>
      <template v-else-if="trs.enabled && step === 'error' && !props.token">
        <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
        <button class="btn btn-primary" @click="(step = 'code'), (error = null)">{{ t('webLogin.enterCode') }}</button>
      </template>
      <button v-else class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.code-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.35rem;
  padding: 0.9rem;
  border-radius: 0.6rem;
  border: 1px dashed color-mix(in srgb, var(--color-redstone-500) 55%, var(--color-base-700));
  background: var(--color-base-900);
}
.account-row {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.45rem 0.6rem;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-900);
  cursor: pointer;
}
.account-row:hover {
  border-color: var(--color-base-600);
}
.account-row.is-on {
  border-color: color-mix(in srgb, var(--color-redstone-500) 70%, var(--color-base-700));
  background: color-mix(in srgb, var(--color-redstone-500) 8%, var(--color-base-900));
}
.account-row:has(input:focus-visible) {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: 2px;
}
.dot {
  width: 0.85rem;
  height: 0.85rem;
  border-radius: 9999px;
  border: 2px solid var(--color-base-600);
}
.is-on .dot {
  border-color: var(--color-redstone-400);
  background: radial-gradient(circle, var(--color-redstone-400) 40%, transparent 45%);
}
</style>
