<script setup lang="ts">
import type { Account, DeviceCode } from '~/types'

const accounts = useAccountsStore()

type LoginMode = 'browser' | 'code'
const login = ref<{ mode: LoginMode; code: DeviceCode | null } | null>(null)
const error = ref<string | null>(null)
const notApproved = ref(false)
const copied = ref(false)
const toRemove = ref<Account | null>(null)

const route = useRoute()
const router = useRouter()

onMounted(() => accounts.load().catch((e) => (error.value = errorMessage(e))))

// „Account hinzufügen“ aus dem Konto-Menü der Titelleiste startet die Anmeldung direkt.
watch(
  () => route.query.add,
  (add) => {
    if (add !== 'browser') return
    router.replace({ query: {} })
    if (!login.value) start('browser')
  },
  { immediate: true },
)

async function start(mode: LoginMode) {
  error.value = null
  notApproved.value = false
  login.value = { mode, code: null }
  try {
    if (mode === 'browser') await accounts.loginBrowser()
    else await accounts.loginDeviceCode((code) => login.value && (login.value.code = code))
  } catch (e) {
    if (!isCancelled(e)) {
      error.value = errorMessage(e)
      notApproved.value = e instanceof BackendError && e.kind === 'auth_not_approved'
    }
  } finally {
    login.value = null
  }
}

function cancel() {
  backend.cancelLogin().catch(() => {})
}

async function copyCode() {
  if (!login.value?.code) return
  try {
    await navigator.clipboard.writeText(login.value.code.userCode)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // Dann muss der Code eben abgetippt werden.
  }
}

async function run(action: () => Promise<void>) {
  error.value = null
  try {
    await action()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function confirmRemove() {
  const account = toRemove.value
  toRemove.value = null
  if (account) await run(() => accounts.remove(account.id))
}
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <PageHeader :title="t('accounts.title')" :subtitle="t('accounts.subtitle')">
      <button class="btn btn-ghost" :disabled="!!login" @click="start('code')">{{ t('accounts.signInWithCode') }}</button>
      <button class="btn btn-primary" :disabled="!!login" @click="start('browser')">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        {{ t('accounts.add') }}
      </button>
    </PageHeader>

    <div v-if="error" role="alert" class="card mb-4 px-4 py-3 text-sm" :class="notApproved ? 'border-warn/40 text-warn' : 'border-redstone-600/50 text-redstone-300'">
      {{ error }}
      <p v-if="notApproved" class="mt-1 text-xs text-base-400">{{ t('accounts.login.notApprovedHint') }}</p>
    </div>

    <ul v-if="accounts.items.length" class="space-y-2">
      <li v-for="a in accounts.items" :key="a.id" class="card flex items-center gap-3 p-3" :class="{ 'border-redstone-600/60': a.active }">
        <SkinHead :skin-url="a.skinUrl" :name="a.name" :size="40" />
        <div class="min-w-0 flex-1">
          <p class="truncate font-medium">{{ a.name }}</p>
          <p class="text-xs text-base-400">{{ a.active ? t('accounts.activeHint') : t('accounts.microsoftAccount') }}</p>
        </div>
        <button v-if="!a.active" class="btn btn-ghost" @click="run(() => accounts.setActive(a.id))">{{ t('accounts.use') }}</button>
        <span v-else class="rounded-full bg-redstone-900 px-2.5 py-1 text-xs font-medium text-redstone-300">{{ t('common.status.active') }}</span>
        <button class="btn btn-ghost px-2.5 hover:text-redstone-300" :title="t('accounts.signOut')" :aria-label="t('accounts.signOut')" @click="toRemove = a">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 8l-4 4 4 4M6 12h10" />
          </svg>
        </button>
      </li>
    </ul>

    <RedstoneEmpty
      v-else-if="accounts.loaded"
      :seed="0x77"
      :title="t('accounts.empty.title')"
      :text="t('accounts.empty.text')"
    >
      <button class="btn btn-primary" :disabled="!!login" @click="start('browser')">{{ t('accounts.login.signInMicrosoft') }}</button>
    </RedstoneEmpty>

    <BaseDialog v-if="login" :title="t('accounts.login.signInMicrosoft')" @close="cancel">
      <template v-if="login.mode === 'browser'">
        <p class="text-sm text-base-200">{{ t('accounts.login.browserOpened') }}</p>
      </template>
      <template v-else-if="login.code">
        <p class="text-sm text-base-200">{{ t('accounts.login.enterCode') }}</p>
        <button
          class="mt-3 w-full rounded-md border border-base-700 bg-base-950 py-3 text-center font-mono text-2xl font-bold tracking-[0.3em] text-base-50 select-text hover:border-redstone-500"
          :title="t('accounts.login.copyCode')"
          @click="copyCode"
        >
          {{ login.code.userCode }}
        </button>
        <p class="mt-2 text-center text-xs text-base-400">
          {{ copied ? t('common.status.copied') : t('accounts.login.clickToCopy') }} ·
          <i18n-t keypath="accounts.login.page" tag="span" scope="global">
            <template #url><span class="font-mono select-text">{{ login.code.verificationUri }}</span></template>
          </i18n-t>
        </p>
      </template>
      <p v-else class="text-sm text-base-400">{{ t('accounts.login.requestingCode') }}</p>

      <p class="mt-4 flex items-center gap-2 text-xs text-base-400">
        <span class="size-2 animate-pulse rounded-full bg-redstone-400" /> {{ t('accounts.login.waiting') }}
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="cancel">{{ t('common.actions.cancel') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="toRemove" :title="t('accounts.remove.title')" @close="toRemove = null">
      <i18n-t keypath="accounts.remove.text" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ toRemove.name }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="toRemove = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmRemove">{{ t('accounts.signOut') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
