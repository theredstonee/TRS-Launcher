<script setup lang="ts">
import type { TrsFriend } from '~/utils/trs'

// Big Picture „Freunde“: wer online ist (TRS-Dienste). Spielt ein Freund auf einem
// Server, tritt A bei – über denselben Weg wie „Beitreten“ in Sozial.
const trs = useTrsStore()

const online = computed(() => trsSortFriends((trs.friends?.friends ?? []).filter((f) => f.presence)))
const offline = computed(() => (trs.friends?.friends.length ?? 0) - online.value.length)

onMounted(() => {
  if (trs.enabled && !trs.friends) void trs.loadFriends()
})

function server(f: TrsFriend): string | null {
  return f.presence?.game?.server ?? null
}

function join(f: TrsFriend) {
  const address = server(f)
  if (address) void useJoinStore().request(address, f.presence?.game?.version ?? null)
}
</script>

<template>
  <section aria-labelledby="bp-friends">
    <div class="mb-6 flex flex-wrap items-end justify-between gap-4">
      <h2 id="bp-friends" class="bp-heading">{{ t('bigPicture.friends.title') }}</h2>
      <p v-if="trs.friends && offline > 0" class="text-lg text-base-400">{{ t('bigPicture.friends.offline', { count: offline }, offline) }}</p>
    </div>

    <div v-if="!trs.enabled" class="bp-empty">
      <p class="text-2xl text-base-50">{{ t('bigPicture.friends.offTitle') }}</p>
      <p class="mt-3">{{ t('bigPicture.friends.offText') }}</p>
    </div>

    <div v-else-if="!trs.friends" class="grid gap-6" style="grid-template-columns: repeat(auto-fill, minmax(24rem, 1fr))">
      <div v-for="i in 3" :key="i" class="skeleton h-28 rounded-2xl" />
    </div>

    <div v-else-if="online.length" class="grid gap-6" style="grid-template-columns: repeat(auto-fill, minmax(24rem, 1fr))">
      <button
        v-for="f in online"
        :key="f.uuid"
        type="button"
        class="bp-tile flex-row! items-center gap-5 p-5"
        :aria-disabled="!server(f)"
        :aria-label="server(f) ? t('bigPicture.friends.joinNamed', { name: f.name, server: server(f)! }) : f.name"
        @click="join(f)"
      >
        <span class="relative block size-20 shrink-0 overflow-hidden rounded-lg ring-2 ring-base-800">
          <PlayerFace :uuid="f.uuid" :name="f.name" />
        </span>
        <span class="min-w-0 flex-1">
          <span class="block truncate text-2xl font-semibold text-base-50">{{ f.name }}</span>
          <span class="mt-1 flex items-center gap-2 text-base" :class="f.presence?.state === 'in-game' ? 'text-lamp-300' : 'text-ok'">
            <span class="size-2.5 shrink-0" :class="f.presence?.state === 'in-game' ? 'bg-lamp-400' : 'bg-ok'" />
            <span class="truncate">{{ trsPresenceText(f.presence) }}</span>
          </span>
        </span>
        <span v-if="server(f)" class="bp-btn bp-btn-primary min-h-12 shrink-0 px-5 text-lg">{{ t('social.invite.join') }}</span>
      </button>
    </div>

    <div v-else class="bp-empty">
      <p class="text-2xl text-base-50">{{ t('bigPicture.friends.noneOnline') }}</p>
    </div>
  </section>
</template>
