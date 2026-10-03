<script setup lang="ts">
import { defaultExtraAccount } from '~/utils/processes'

// „Nochmal starten“ (zweiter Prozess derselben Instanz, anderes Konto) und „Welchen Prozess
// beenden?“ bei mehreren – ein Dialog für beides, einmal im Layout eingebunden.
const games = useGamesStore()
const accounts = useAccountsStore()
const instances = useInstancesStore()

const startId = computed(() => games.extraPrompt)
const stopId = computed(() => games.stopPrompt)
const instance = computed(() => instances.items.find((i) => i.id === (startId.value ?? stopId.value)) ?? null)
const name = computed(() => instance.value?.name ?? startId.value ?? stopId.value ?? '')

const account = ref<string | null>(null)
const inUse = computed(() => (startId.value ? games.processes(startId.value).flatMap((p) => (p.accountId ? [p.accountId] : [])) : []))
const sameAccount = computed(() => !!account.value && inUse.value.includes(account.value))

watch(
  startId,
  (id) => {
    if (!id) return
    if (!accounts.loaded) accounts.load().catch(() => {})
    account.value = defaultExtraAccount(accounts.items, accounts.active?.id ?? null, inUse.value)
  },
  { immediate: true },
)
// Nach dem Laden der Konten die Vorauswahl nachziehen.
watch(
  () => accounts.items,
  (items) => {
    if (startId.value && !account.value) account.value = defaultExtraAccount(items, accounts.active?.id ?? null, inUse.value)
  },
)

function accountName(id: string | null): string {
  return accounts.items.find((a) => a.id === id)?.name ?? t('play.again.unknownAccount')
}

async function start() {
  const id = startId.value
  if (!id) return
  games.extraPrompt = null
  await games.launchExtra(id, account.value)
}

const running = computed(() => (stopId.value ? games.processes(stopId.value) : []))
// Schließt sich selbst, wenn nur noch ein Prozess übrig ist.
watch(running, (list) => {
  if (stopId.value && list.length < 2) games.stopPrompt = null
})
function stopOne(key: string) {
  if (stopId.value) void games.stop(stopId.value, key)
}
function stopAll() {
  const id = stopId.value
  games.stopPrompt = null
  if (id) void games.stop(id)
}
function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <BaseDialog v-if="startId" :title="t('play.again.title', { name })" @close="games.extraPrompt = null">
    <p class="text-sm text-base-300">{{ t('play.again.intro') }}</p>
    <label class="label mt-3" for="extra-account">{{ t('play.again.account') }}</label>
    <select id="extra-account" v-model="account" class="field" data-testid="extra-account">
      <option v-for="a in accounts.items" :key="a.id" :value="a.id">{{ a.name }}{{ a.active ? ` (${t('play.again.active')})` : '' }}</option>
      <option v-if="!accounts.items.length" :value="null">{{ t('play.again.noAccounts') }}</option>
    </select>
    <p v-if="sameAccount" class="mt-2 text-xs text-lamp-300">{{ t('play.again.sameAccount') }}</p>
    <p class="mt-3 rounded-md bg-base-900 px-3 py-2 text-xs text-lamp-300" role="note" data-testid="extra-world-hint">
      {{ t('play.again.worldHint') }}
    </p>
    <p class="mt-2 text-xs text-base-400">{{ t('play.again.noLink') }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="games.extraPrompt = null">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="games.extraBusy.has(startId)" data-testid="extra-start" @click="start">{{ t('play.again.start') }}</button>
    </template>
  </BaseDialog>

  <BaseDialog v-else-if="stopId" :title="t('play.stopChoice.title', { name })" @close="games.stopPrompt = null">
    <p class="mb-3 text-sm text-base-300">{{ t('play.stopChoice.intro', running.length) }}</p>
    <ul class="space-y-2">
      <li v-for="(p, i) in running" :key="p.key" class="flex items-center gap-3 rounded-md bg-base-900 px-3 py-2">
        <span class="size-2 shrink-0 animate-lamp rounded-full bg-ok" aria-hidden="true" />
        <span class="min-w-0 flex-1">
          <span class="block truncate text-sm text-base-50">{{ t('play.stopChoice.process', { n: i + 1 }) }}<template v-if="p.accountId"> · {{ accountName(p.accountId) }}</template></span>
          <span class="block text-[11px] text-base-400">{{ t('play.stopChoice.since', { time: clock(p.startedAt) }) }}</span>
        </span>
        <button class="btn btn-ghost px-3 py-1 text-xs" :data-testid="`stop-${p.key}`" @click="stopOne(p.key)">{{ t('common.actions.stop') }}</button>
      </li>
    </ul>
    <template #actions>
      <button class="btn btn-ghost" @click="games.stopPrompt = null">{{ t('common.actions.close') }}</button>
      <button class="btn btn-danger" data-testid="stop-all" @click="stopAll">{{ t('play.stopChoice.all') }}</button>
    </template>
  </BaseDialog>
</template>
