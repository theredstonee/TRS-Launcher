<script setup lang="ts">
// Während Minecraft startet: ein kurzes Angebot unten rechts, auf Wunsch das Spiel in einem Fenster.
// Es öffnet sich nie von selbst und endet auch nicht von selbst: Eine laufende Partie gehört dem Spieler.
import type { GameId } from '~/utils/minigames/replay'
import type { FinishedRun } from '~/utils/minigames/session'

/** So lange bleibt das Angebot nach dem Prozessstart: Minecraft braucht danach noch, bis das Fenster steht. */
const OFFER_AFTER_START_MS = 30_000

const games = useGamesStore()
const instances = useInstancesStore()
const settings = useSettingsStore()
const store = useMinigamesStore()

const open = ref(false)
const game = ref<GameId>('stapler')
const dismissed = ref(false)
const lingering = ref(false)
let lingerTimer: ReturnType<typeof setTimeout> | null = null

const preparing = computed(() => instances.items.some(i => games.state(i.id).phase === 'preparing'))
const enabled = computed(() => settings.current?.ui.minigamesWhileLoading !== false && platformCaps.value.gameLaunch !== false)
const offer = computed(() => enabled.value && !dismissed.value && !open.value && (preparing.value || lingering.value))

watch(preparing, (now, before) => {
  if (lingerTimer) clearTimeout(lingerTimer)
  if (now) {
    dismissed.value = false
    lingering.value = false
  }
  else if (before) {
    lingering.value = true
    lingerTimer = setTimeout(() => {
      lingering.value = false
    }, OFFER_AFTER_START_MS)
  }
})

onBeforeUnmount(() => {
  if (lingerTimer) clearTimeout(lingerTimer)
})

function start(id: GameId) {
  game.value = id
  open.value = true
}

async function onFinished(run: FinishedRun) {
  await store.submit(run)
}
</script>

<template>
  <div v-if="offer" class="fixed bottom-4 right-4 z-40 w-72 rounded-xl border border-base-700 bg-base-850 p-4 shadow-xl" role="status">
    <div class="flex items-start justify-between gap-2">
      <p class="text-sm font-semibold text-base-50">{{ t('minigames.loading.title') }}</p>
      <button type="button" class="text-base-400 hover:text-base-100" :aria-label="t('common.actions.close')" @click="dismissed = true">
        <AppIcon name="close" class="size-4" />
      </button>
    </div>
    <p class="mt-1 text-xs text-base-300">{{ t('minigames.loading.text') }}</p>
    <div class="mt-3 flex gap-2">
      <button type="button" class="btn-secondary flex-1 text-xs" @click="start('stapler')">{{ t('minigames.stapler.name') }}</button>
      <button type="button" class="btn-secondary flex-1 text-xs" @click="start('loren')">{{ t('minigames.loren.name') }}</button>
    </div>
  </div>

  <div v-if="open" class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-6" role="dialog" aria-modal="true" :aria-label="t(game === 'stapler' ? 'minigames.stapler.name' : 'minigames.loren.name')">
    <div class="relative flex h-[min(620px,90vh)] w-[min(820px,94vw)] rounded-xl bg-base-900">
      <MinigamesMinigamePlayer :game="game" compact class="w-full" @finished="onFinished" />
      <button type="button" class="absolute right-3 top-3 z-10 rounded-md bg-black/60 p-1.5 text-white hover:bg-black/80" :aria-label="t('common.actions.close')" @click="open = false">
        <AppIcon name="close" class="size-4" />
      </button>
    </div>
  </div>
</template>
