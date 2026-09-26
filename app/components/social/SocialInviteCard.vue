<script lang="ts">
import type { InviteStatus } from '~/utils/chat'

// Status je Adresse kurz merken (der TRS-Server pingt, 60 s Cache dort) –
// viele Karten mit derselben Adresse fragen nur einmal.
const cache = new Map<string, { at: number; status: Promise<InviteStatus | null> }>()
const TTL_MS = 60_000

function statusFor(address: string): Promise<InviteStatus | null> {
  const hit = cache.get(address)
  if (hit && Date.now() - hit.at < TTL_MS) return hit.status
  const status = backend.social.serverStatus(address).catch(() => null)
  cache.set(address, { at: Date.now(), status })
  return status
}
</script>

<script setup lang="ts">
import type { ChatInvite } from '~/utils/chat'
import { versionHint } from '~/stores/join'

// Server-Einladung als Karte: Icon, Name/Adresse, Spielerzahl (live vom
// TRS-Server geprüft, nie vom Absender) und „Beitreten“ – startet die passende
// Instanz und verbindet direkt.
const props = defineProps<{ invite: ChatInvite }>()
const status = ref<InviteStatus | null>(null)
const loading = ref(true)

watch(
  () => props.invite.address,
  async (address) => {
    loading.value = true
    const result = await statusFor(address)
    if (address === props.invite.address) {
      status.value = result
      loading.value = false
    }
  },
  { immediate: true },
)

const line = computed(() => {
  const s = status.value
  if (loading.value) return t('social.invite.checking')
  if (!s) return t('social.invite.unknown')
  if (s.online) return t('social.invite.players', { online: s.playersOnline ?? 0, max: s.playersMax ?? 0 })
  if (s.reason === 'private_address') return t('social.invite.private')
  return t('social.invite.offline')
})
const icon = computed(() => (status.value?.icon?.startsWith('data:image/png;base64,') ? status.value.icon : null))

function join() {
  void useJoinStore().request(props.invite.address, versionHint(status.value?.version))
}
</script>

<template>
  <div class="invite-card flex items-center gap-3 rounded-lg p-2.5" data-testid="invite-card">
    <span class="grid size-12 shrink-0 place-items-center overflow-hidden rounded-md bg-base-950 ring-1 ring-base-700">
      <img v-if="icon" :src="icon" alt="" class="size-full" style="image-rendering: pixelated" />
      <SocialIcon v-else name="server" class="size-6 text-base-400" />
    </span>
    <span class="min-w-0 flex-1">
      <span class="block truncate text-sm font-semibold text-base-50">{{ invite.name || invite.address }}</span>
      <span v-if="invite.name" class="block truncate font-mono text-[11px] text-base-400">{{ invite.address }}</span>
      <span class="flex items-center gap-1.5 text-xs" :class="status?.online ? 'text-base-200' : 'text-base-400'">
        <span class="size-1.5 rounded-full" :class="loading ? 'bg-base-600' : status?.online ? 'bg-ok' : 'bg-base-600'" />
        {{ line }}
        <span v-if="status?.online && status.version" class="truncate text-base-400">· {{ status.version }}</span>
      </span>
    </span>
    <button class="btn btn-primary shrink-0 px-3 py-1.5 text-xs" data-testid="invite-join" @click="join">{{ t('social.invite.join') }}</button>
  </div>
</template>

<style scoped>
.invite-card {
  min-width: 17rem;
  background: var(--color-base-900);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
  color: var(--color-base-50);
}
</style>
