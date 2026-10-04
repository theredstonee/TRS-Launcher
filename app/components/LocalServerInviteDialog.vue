<script setup lang="ts">
import { newNonce } from '~/utils/chat'
import type { InviteMethod, LocalServer, ServerAddresses } from '~/utils/serverExport'

// Freunde zu einem lokalen Server einladen. Bester Weg: TRS Relay – die Freunde
// bekommen eine Welt-Einladung (Toast + Karte im Chat), ihr Launcher startet die
// passende Version und verbindet über das Relay. Sonst eine Server-Karte im Chat
// mit der e4mc- bzw. öffentlichen Adresse.
const props = defineProps<{ server: LocalServer; addresses: ServerAddresses | null }>()
const emit = defineEmits<{ close: [] }>()

const servers = useLocalServersStore()
const trs = useTrsStore()
const toasts = useToasts()

const share = computed(() => servers.shareOf(props.server.id))
const methods = computed(() => inviteMethods(share.value, props.addresses))
const method = ref<InviteMethod>('relay')
const picked = ref<Set<string>>(new Set())
const sent = ref<Set<string>>(new Set())
const busy = ref(false)
const filter = ref('')

onMounted(() => {
  if (!trs.friends) void trs.loadFriends()
})
watch(methods, (list) => {
  if (!list.includes(method.value)) method.value = list[0] ?? 'relay'
})

const friends = computed(() => {
  const q = filter.value.trim().toLowerCase()
  return (trs.friends?.friends ?? []).filter((f) => !q || f.name.toLowerCase().includes(q))
})

function toggle(uuid: string) {
  const next = new Set(picked.value)
  if (next.has(uuid)) next.delete(uuid)
  else next.add(uuid)
  picked.value = next
}

function methodLabel(m: InviteMethod): string {
  if (m === 'relay') return t('localServers.invite.method.relay')
  return t(`localServers.invite.method.${m}`, { address: inviteAddress(m, share.value, props.addresses) ?? '' })
}

/** Relay-Weg: Raum bei Bedarf einschalten, dann über die API einladen. */
async function inviteViaRelay(uuids: string[]): Promise<string[]> {
  if (!share.value.relay.roomId && !(await servers.setShared(props.server.id, 'relay', true))) return []
  const outcomes = await backend.localServers.invite(props.server.id, uuids)
  for (const o of outcomes.filter((o) => !o.ok)) {
    const name = trs.friends?.friends.find((f) => f.uuid === o.uuid)?.name ?? '?'
    toasts.error(t('localServers.invite.failed', { name, reason: inviteErrorText(o.code) }))
  }
  return outcomes.filter((o) => o.ok).map((o) => o.uuid)
}

/** Adress-Weg: Server-Karte als Direktnachricht an jeden Freund. */
async function inviteViaCard(uuids: string[], address: string): Promise<string[]> {
  const done: string[] = []
  for (const uuid of uuids) {
    try {
      const conversation = await backend.social.openDm(uuid)
      await backend.social.send(conversation.id, inviteCard(address, props.server.name, newNonce()))
      done.push(uuid)
    } catch (e) {
      toasts.error(e)
    }
  }
  return done
}

async function send() {
  if (!picked.value.size || busy.value) return
  busy.value = true
  try {
    const uuids = [...picked.value].slice(0, 20)
    const address = inviteAddress(method.value, share.value, props.addresses)
    const done = method.value === 'relay' ? await inviteViaRelay(uuids) : address ? await inviteViaCard(uuids, address) : []
    sent.value = new Set([...sent.value, ...done])
    picked.value = new Set([...picked.value].filter((u) => !done.includes(u)))
    if (done.length) toasts.ok(t('localServers.invite.done', { count: done.length }, done.length))
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="t('localServers.invite.title', { name: server.name })" @close="emit('close')">
    <fieldset>
      <legend class="mb-1.5 text-xs font-medium text-base-300">{{ t('localServers.invite.how') }}</legend>
      <label v-for="m in methods" :key="m" class="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-base-800">
        <input v-model="method" type="radio" name="invite-method" :value="m" class="accent-redstone-500" :disabled="busy" />
        <span class="min-w-0 truncate">{{ methodLabel(m) }}</span>
      </label>
    </fieldset>
    <p class="mt-2 text-xs leading-relaxed text-base-400">
      {{ method === 'relay' ? t('localServers.invite.relayHint') : t('localServers.invite.cardHint') }}
    </p>

    <div class="mt-3">
      <p v-if="trs.friends && !trs.friends.friends.length" class="text-sm text-base-400">{{ t('localServers.invite.noFriends') }}</p>
      <template v-else>
        <input
          v-if="(trs.friends?.friends.length ?? 0) > 6"
          v-model="filter"
          class="field mb-2 h-8 py-0 text-xs"
          maxlength="16"
          :placeholder="t('localServers.invite.search')"
          :aria-label="t('localServers.invite.search')"
        />
        <ul class="max-h-52 space-y-0.5 overflow-y-auto pr-1" data-testid="server-invite-friends">
          <li v-for="f in friends" :key="f.uuid">
            <label class="flex cursor-pointer items-center gap-2.5 rounded-md px-2 py-1.5 hover:bg-base-850" :class="{ 'opacity-60': sent.has(f.uuid) }">
              <input
                type="checkbox"
                class="size-4 accent-redstone-500"
                :checked="picked.has(f.uuid) || sent.has(f.uuid)"
                :disabled="sent.has(f.uuid) || busy"
                @change="toggle(f.uuid)"
              />
              <span class="block size-5 overflow-hidden rounded"><PlayerFace :uuid="f.uuid" :name="f.name" /></span>
              <span class="min-w-0 flex-1 truncate text-sm">{{ f.name }}</span>
              <span v-if="sent.has(f.uuid)" class="text-[11px] text-ok">{{ t('localServers.invite.sent') }}</span>
            </label>
          </li>
        </ul>
      </template>
    </div>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
      <button class="btn btn-primary" :disabled="!picked.size || busy" data-testid="server-invite-send" @click="send">
        {{ busy ? t('localServers.invite.sending') : t('localServers.invite.send', { count: picked.size }, picked.size) }}
      </button>
    </template>
  </BaseDialog>
</template>
