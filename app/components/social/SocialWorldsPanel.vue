<script setup lang="ts">
import { formatJoinCode, parseJoinCode, roomFull, worldVersionLabel, type HostingRoom } from '~/utils/hosting'

// „Welten“: offene Einzelspielerwelten von Freunden (und alle, zu denen man
// eingeladen ist oder angefragt hat) mit „Beitreten“/„Anfragen“, Beitreten per
// Code und – wenn man selbst gerade im Spiel hostet – der Stand der eigenen Welt
// (nur zum Ansehen; verwaltet wird im Spiel). Alles live über den Echtzeit-Kanal.
const props = defineProps<{ search: string }>()
const hosting = useHostingStore()

const code = ref('')
const codeValid = computed(() => parseJoinCode(code.value) !== null)

const rooms = computed(() => {
  const q = props.search.trim().toLocaleLowerCase()
  if (!q) return hosting.rooms
  return hosting.rooms.filter((r) => r.name.toLocaleLowerCase().includes(q) || r.host.name.toLocaleLowerCase().includes(q))
})

const members = computed(() => {
  const list = hosting.mine?.members ?? []
  return {
    accepted: list.filter((m) => m.state === 'accepted'),
    requested: list.filter((m) => m.state === 'requested'),
    invited: list.filter((m) => m.state === 'invited'),
  }
})

onMounted(() => {
  if (!hosting.loaded && !hosting.loading) void hosting.load()
})

function joinCode() {
  const parsed = parseJoinCode(code.value)
  if (!parsed) return
  void hosting.join({ code: parsed }).then(() => {
    code.value = ''
  })
}

function stateLabel(r: HostingRoom): string | null {
  if (r.myState === 'accepted') return t('social.hosting.state.accepted')
  if (r.myState === 'invited') return t('social.hosting.state.invited')
  if (r.myState === 'requested' || hosting.waiting[r.id]) return t('social.hosting.state.requested')
  return null
}

function requested(r: HostingRoom): boolean {
  return r.myState === 'requested' || !!hosting.waiting[r.id]
}

/** Eingeladen oder schon zugelassen → „Beitreten“, sonst „Anfragen“. */
function canEnter(r: HostingRoom): boolean {
  return r.myState === 'invited' || r.myState === 'accepted'
}

function modeLabel(r: HostingRoom): string {
  return t(`social.hosting.modes.${r.gameMode}`)
}
</script>

<template>
  <div class="grid min-h-0 flex-1 grid-cols-1 gap-3 overflow-y-auto lg:grid-cols-3 lg:overflow-hidden" data-testid="worlds-panel">
    <!-- Welten von Freunden -->
    <section class="card flex min-h-0 flex-col lg:col-span-2">
      <h2 class="display flex items-center gap-2 border-b border-base-800 px-4 py-3 text-base text-base-50">
        {{ t('social.hosting.friendsWorlds') }}
        <span v-if="hosting.rooms.length" class="text-xs text-base-400">{{ hosting.rooms.length }}</span>
        <button class="btn-icon ml-auto size-8" :disabled="hosting.loading" :title="t('social.hosting.refresh')" :aria-label="t('social.hosting.refresh')" @click="hosting.load()">
          <SocialIcon name="sync" class="size-4" :class="{ 'animate-spin': hosting.loading }" />
        </button>
      </h2>
      <ul v-if="rooms.length" class="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-2" data-testid="worlds-list">
        <li v-for="r in rooms" :key="r.id" class="flex items-center gap-3 rounded-lg bg-base-850 px-3 py-2.5" data-testid="world-row">
          <span class="relative shrink-0">
            <span class="block size-10 overflow-hidden rounded-md"><PlayerFace :uuid="r.host.uuid" :name="r.host.name" /></span>
            <span class="absolute -right-1 -bottom-1 grid size-5 place-items-center rounded-md bg-base-900 ring-2 ring-base-850">
              <SocialIcon name="world" class="size-3 text-redstone-300" />
            </span>
          </span>
          <div class="min-w-0 flex-1">
            <p class="flex items-center gap-2 truncate text-sm font-semibold text-base-50">
              {{ r.name }}
              <span v-if="stateLabel(r)" class="badge bg-redstone-900/50 px-1.5 py-0 text-[10px] text-redstone-300">{{ stateLabel(r) }}</span>
            </p>
            <p class="truncate text-xs text-base-300">
              {{ t('social.hosting.hostedBy', { host: r.host.name }) }} · {{ worldVersionLabel(r) }} · {{ modeLabel(r) }}
            </p>
            <p class="flex items-center gap-1.5 text-xs text-base-400">
              <span class="size-1.5 rounded-full bg-ok" />
              {{ t('social.invite.players', { online: r.players, max: r.maxPlayers }) }}
              <template v-if="!r.open && !canEnter(r)"> · {{ t('social.hosting.closedForRequests') }}</template>
            </p>
          </div>
          <template v-if="requested(r)">
            <button class="btn btn-ghost px-2.5 py-1 text-xs" disabled>{{ t('social.hosting.requested') }}</button>
            <button class="btn btn-ghost px-2.5 py-1 text-xs" data-testid="world-withdraw" @click="hosting.leave(r.id)">{{ t('social.hosting.withdraw') }}</button>
          </template>
          <template v-else>
            <button
              v-if="canEnter(r)"
              class="btn btn-primary px-2.5 py-1 text-xs"
              :disabled="!!hosting.busy[r.id] || (roomFull(r) && r.myState !== 'accepted')"
              data-testid="world-enter"
              @click="hosting.join({ room: r })"
            >
              {{ roomFull(r) && r.myState !== 'accepted' ? t('social.hosting.full') : t('social.hosting.join') }}
            </button>
            <button
              v-else
              class="btn btn-primary px-2.5 py-1 text-xs"
              :disabled="!!hosting.busy[r.id] || !r.open || roomFull(r)"
              data-testid="world-ask"
              @click="hosting.join({ room: r })"
            >
              {{ roomFull(r) ? t('social.hosting.full') : t('social.hosting.ask') }}
            </button>
            <button v-if="r.myState === 'invited'" class="btn btn-ghost px-2.5 py-1 text-xs" @click="hosting.leave(r.id)">{{ t('social.hosting.decline') }}</button>
          </template>
        </li>
      </ul>
      <div v-else class="grid flex-1 place-items-center p-6">
        <RedstoneEmpty
          :title="props.search.trim() ? t('social.chat.noMatches') : t('social.hosting.emptyTitle')"
          :text="props.search.trim() ? '' : t('social.hosting.emptyText')"
          :seed="0x77"
          compact
        />
      </div>
    </section>

    <div class="flex min-h-0 flex-col gap-3 lg:overflow-y-auto">
      <!-- Mit Code beitreten -->
      <section class="card p-4">
        <h2 class="display mb-1 text-base text-base-50">{{ t('social.hosting.joinWithCode') }}</h2>
        <p class="mb-3 text-xs text-base-400">{{ t('social.hosting.codeHint') }}</p>
        <form class="flex gap-2" @submit.prevent="joinCode">
          <input
            v-model="code"
            class="field min-w-0 flex-1 font-mono tracking-widest uppercase"
            maxlength="9"
            placeholder="K7Q-M2X"
            autocomplete="off"
            spellcheck="false"
            :aria-label="t('social.hosting.code')"
            data-testid="world-code"
          />
          <button class="btn btn-primary" :disabled="!codeValid || !!hosting.busy[parseJoinCode(code) ?? '']" data-testid="world-code-join">
            {{ t('social.hosting.ask') }}
          </button>
        </form>
      </section>

      <!-- Eigene Welt (wenn man im Spiel hostet) -->
      <section v-if="hosting.mine" class="card p-4" data-testid="my-world">
        <h2 class="display mb-2 flex items-center gap-2 text-base text-base-50">
          <SocialIcon name="world" class="size-4 text-redstone-300" />{{ t('social.hosting.yourWorld') }}
        </h2>
        <p class="truncate text-sm font-semibold text-base-50">{{ hosting.mine.name }}</p>
        <p class="mb-3 text-xs text-base-400">{{ worldVersionLabel(hosting.mine) }} · {{ modeLabel(hosting.mine) }}</p>
        <dl class="grid grid-cols-2 gap-2 text-xs">
          <div class="rounded-lg bg-base-850 px-3 py-2">
            <dt class="text-base-400">{{ t('social.hosting.code') }}</dt>
            <dd class="font-mono text-sm text-base-50" data-testid="my-world-code">{{ formatJoinCode(hosting.mine.code) || '—' }}</dd>
          </div>
          <div class="rounded-lg bg-base-850 px-3 py-2">
            <dt class="text-base-400">{{ t('social.hosting.players') }}</dt>
            <dd class="text-sm text-base-50">{{ hosting.mine.players }}/{{ hosting.mine.maxPlayers }}</dd>
          </div>
        </dl>
        <div v-if="members.accepted.length || members.requested.length || members.invited.length" class="mt-3 space-y-2">
          <div v-for="group in (['accepted', 'requested', 'invited'] as const)" :key="group">
            <template v-if="members[group].length">
              <h3 class="section-title mb-1 text-[11px] text-base-400">{{ t(`social.hosting.members.${group}`) }}</h3>
              <ul class="flex flex-wrap gap-1.5">
                <li v-for="m in members[group]" :key="m.uuid" class="flex items-center gap-1.5 rounded-md bg-base-850 py-1 pr-2 pl-1 text-xs text-base-100">
                  <span class="block size-5 overflow-hidden rounded"><PlayerFace :uuid="m.uuid" :name="m.name" /></span>{{ m.name }}
                </li>
              </ul>
            </template>
          </div>
        </div>
        <p class="mt-3 text-[11px] text-base-400">{{ t('social.hosting.manageInGame') }}</p>
      </section>

      <!-- So geht's -->
      <section class="card p-4 text-xs text-base-400">
        <h2 class="display mb-1.5 text-sm text-base-100">{{ t('social.hosting.howTitle') }}</h2>
        <p class="mb-2">{{ t('social.hosting.howHost') }}</p>
        <p>{{ t('social.hosting.privacyNote') }}</p>
      </section>
    </div>
  </div>
</template>
