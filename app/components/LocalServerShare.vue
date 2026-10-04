<script setup lang="ts">
import type { LocalServer, ServerAddresses } from '~/utils/serverExport'

// „Mit Freunden spielen“ für einen laufenden lokalen Server: Adressen (PC, Heimnetz,
// Internet mit Portfreigabe-Hinweis und Erreichbarkeits-Test), TRS Relay (ohne
// Portfreigabe, nur eingeladene TRS-Freunde), öffentlicher e4mc-Link und „Freunde einladen“.
const props = defineProps<{ server: LocalServer }>()

const servers = useLocalServersStore()
const toasts = useToasts()

const share = computed(() => servers.shareOf(props.server.id))
const addresses = ref<ServerAddresses | null>(null)
const loadingAddresses = ref(false)
const copied = ref<string | null>(null)
const busy = ref<{ relay: boolean; e4mc: boolean }>({ relay: false, e4mc: false })
const warnE4mc = ref(false)
const understood = ref(false)
const inviteOpen = ref(false)

type Reach = 'checking' | 'reachable' | 'unreachable' | 'failed'
const reach = ref<Reach | null>(null)

async function loadAddresses(refresh = false) {
  const id = props.server.id
  loadingAddresses.value = true
  try {
    const found = await backend.localServers.addresses(id, refresh)
    if (props.server.id === id) addresses.value = found
  } catch (e) {
    toasts.error(e)
  } finally {
    loadingAddresses.value = false
  }
}

watch(
  () => props.server.id,
  (id) => {
    addresses.value = null
    reach.value = null
    void loadAddresses()
    void servers.loadShare(id)
  },
  { immediate: true },
)

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = text
    setTimeout(() => {
      if (copied.value === text) copied.value = null
    }, 1500)
  } catch {
    // Ohne Zwischenablage bleibt die Adresse sichtbar und markierbar.
  }
}

/** Der TRS-Server pingt die öffentliche Adresse (wie bei Server-Karten im Chat). */
async function checkReach() {
  const address = addresses.value?.public
  if (!address || reach.value === 'checking') return
  reach.value = 'checking'
  try {
    const status = await backend.social.serverStatus(address)
    reach.value = status.online ? 'reachable' : status.reason === 'timeout' || status.reason === 'refused' ? 'unreachable' : 'failed'
  } catch (e) {
    reach.value = 'failed'
    toasts.error(e)
  }
}

async function toggle(kind: 'relay' | 'e4mc', on: boolean) {
  if (busy.value[kind]) return
  if (kind === 'e4mc' && on) {
    // Warnung bei jedem Einschalten – nicht dauerhaft wegklickbar.
    understood.value = false
    warnE4mc.value = true
    return
  }
  busy.value = { ...busy.value, [kind]: true }
  await servers.setShared(props.server.id, kind, on)
  busy.value = { ...busy.value, [kind]: false }
}

async function enableE4mc() {
  if (!understood.value) return
  warnE4mc.value = false
  busy.value = { ...busy.value, e4mc: true }
  await servers.setShared(props.server.id, 'e4mc', true)
  busy.value = { ...busy.value, e4mc: false }
}

const relayOn = computed({
  get: () => share.value.relay.state !== 'off',
  set: (on: boolean) => void toggle('relay', on),
})
const e4mcOn = computed({
  get: () => share.value.e4mc.state !== 'off',
  set: (on: boolean) => void toggle('e4mc', on),
})

const stateTone: Record<string, string> = {
  connecting: 'text-lamp-300',
  online: 'text-ok',
  reconnecting: 'text-lamp-300',
}
const reachTone: Record<Reach, string> = {
  checking: 'text-base-400',
  reachable: 'text-ok',
  unreachable: 'text-redstone-300',
  failed: 'text-base-400',
}
</script>

<template>
  <section class="mt-3 rounded-md bg-base-900/60 p-3 text-xs" data-testid="server-share">
    <div class="flex flex-wrap items-center gap-2">
      <h3 class="flex-1 text-sm font-semibold text-base-100">{{ t('localServers.share.title') }}</h3>
      <button class="btn btn-primary px-3 py-1.5 text-xs" data-testid="server-invite" @click="inviteOpen = true">
        {{ t('localServers.invite.button') }}
      </button>
    </div>

    <!-- Adressen -->
    <dl class="mt-2 grid gap-x-3 gap-y-1.5 sm:grid-cols-[9rem_minmax(0,1fr)]">
      <template v-for="row in (['local', 'lan', 'public'] as const)" :key="row">
        <dt class="text-base-400">{{ t(`localServers.share.${row}`) }}</dt>
        <dd class="flex min-w-0 flex-wrap items-center gap-2">
          <template v-if="addresses?.[row]">
            <code class="truncate rounded bg-base-950 px-1.5 py-0.5 font-mono text-[11.5px] text-base-100 select-all">{{ addresses[row] }}</code>
            <button class="text-redstone-300 hover:text-redstone-200" @click="copy(addresses[row]!)">
              {{ copied === addresses[row] ? t('localServers.share.copied') : t('localServers.share.copy') }}
            </button>
            <template v-if="row === 'public'">
              <button class="text-redstone-300 hover:text-redstone-200 disabled:opacity-50" :disabled="reach === 'checking'" @click="checkReach">
                {{ reach === 'checking' ? t('localServers.share.checking') : t('localServers.share.check') }}
              </button>
              <span v-if="reach && reach !== 'checking'" :class="reachTone[reach]">{{ t(`localServers.share.reach.${reach}`) }}</span>
            </template>
          </template>
          <span v-else class="text-base-500">{{ loadingAddresses ? '…' : t('localServers.share.unknown') }}</span>
          <button v-if="row === 'public' && !loadingAddresses && !addresses?.public" class="text-redstone-300 hover:text-redstone-200" @click="loadAddresses(true)">
            {{ t('localServers.share.retry') }}
          </button>
        </dd>
      </template>
    </dl>
    <p class="mt-1.5 leading-relaxed text-base-500">{{ t('localServers.share.portHint', { port: server.port }) }}</p>

    <!-- TRS Relay -->
    <div class="mt-3 flex items-start gap-3 border-t border-base-800 pt-3">
      <div class="min-w-0 flex-1">
        <p class="font-medium text-base-100">{{ t('localServers.share.relayTitle') }}</p>
        <p class="mt-0.5 leading-relaxed text-base-400">{{ t('localServers.share.relayText') }}</p>
        <p v-if="share.relay.state !== 'off'" class="mt-1" :class="stateTone[share.relay.state]">
          {{ t(`localServers.share.state.${share.relay.state}`) }}
          <template v-if="share.relay.state === 'reconnecting' && shareErrorText(share.relay.error)"> – {{ shareErrorText(share.relay.error) }}</template>
        </p>
        <p v-else-if="share.relay.error" class="mt-1 text-redstone-300">{{ shareErrorText(share.relay.error) }}</p>
      </div>
      <ToggleSwitch v-model="relayOn" :label="t('localServers.share.relayTitle')" :disabled="busy.relay" />
    </div>

    <!-- e4mc -->
    <div class="mt-3 flex items-start gap-3 border-t border-base-800 pt-3">
      <div class="min-w-0 flex-1">
        <p class="font-medium text-base-100">{{ t('localServers.share.e4mcTitle') }}</p>
        <p class="mt-0.5 leading-relaxed text-base-400">{{ t('localServers.share.e4mcText') }}</p>
        <div v-if="share.e4mc.state === 'online' && share.e4mc.address" class="mt-1.5 flex flex-wrap items-center gap-2">
          <code class="truncate rounded bg-base-950 px-1.5 py-0.5 font-mono text-[11.5px] text-ok select-all">{{ share.e4mc.address }}</code>
          <button class="text-redstone-300 hover:text-redstone-200" @click="copy(share.e4mc.address)">
            {{ copied === share.e4mc.address ? t('localServers.share.copied') : t('localServers.share.copy') }}
          </button>
        </div>
        <p v-else-if="share.e4mc.state !== 'off'" class="mt-1" :class="stateTone[share.e4mc.state]">
          {{ t(`localServers.share.state.${share.e4mc.state}`) }}
          <template v-if="share.e4mc.state === 'reconnecting' && shareErrorText(share.e4mc.error)"> – {{ shareErrorText(share.e4mc.error) }}</template>
        </p>
        <p v-if="share.e4mc.state !== 'off'" class="mt-1 text-lamp-300">{{ t('localServers.share.e4mcActive') }}</p>
      </div>
      <ToggleSwitch v-model="e4mcOn" :label="t('localServers.share.e4mcTitle')" :disabled="busy.e4mc" />
    </div>

    <BaseDialog v-if="warnE4mc" :title="t('localServers.share.e4mcWarnTitle')" @close="warnE4mc = false">
      <p class="text-sm text-base-200">{{ t('localServers.share.e4mcWarn') }}</p>
      <p class="mt-2 text-xs text-base-400">{{ t('localServers.share.e4mcPrivacy') }}</p>
      <label class="mt-3 flex cursor-pointer items-center gap-2 text-sm">
        <input v-model="understood" type="checkbox" class="size-4 accent-redstone-500" data-testid="e4mc-understood" />
        {{ t('localServers.share.understood') }}
      </label>
      <template #actions>
        <button class="btn btn-ghost" @click="warnE4mc = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!understood" @click="enableE4mc">{{ t('localServers.share.enable') }}</button>
      </template>
    </BaseDialog>

    <LocalServerInviteDialog v-if="inviteOpen" :server="server" :addresses="addresses" @close="inviteOpen = false" />
  </section>
</template>
