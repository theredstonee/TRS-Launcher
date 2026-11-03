<script setup lang="ts">
// Reiter „Minispiele“: Spiel wählen, spielen, weltweite Allzeit-Rangliste.
import type { Leaderboard, LeaderboardEntry, SubmitResult } from '~/stores/minigames'
import type { GameId } from '~/utils/minigames/replay'
import type { FinishedRun } from '~/utils/minigames/session'

const store = useMinigamesStore()
const trs = useTrsStore()
const toasts = useToasts()

const game = ref<GameId>('stapler')
const board = shallowRef<Leaderboard | null>(null)
const boardError = ref(false)
const boardLoading = ref(false)
const result = shallowRef<SubmitResult | null>(null)
const submitting = ref(false)
const removing = ref<LeaderboardEntry | null>(null)
const removeReason = ref('')
const canManage = computed(() => trs.team?.permissions.includes('minigames.manage') ?? false)

const games: { id: GameId, name: 'minigames.stapler.name' | 'minigames.loren.name', tagline: 'minigames.stapler.tagline' | 'minigames.loren.tagline' }[] = [
  { id: 'stapler', name: 'minigames.stapler.name', tagline: 'minigames.stapler.tagline' },
  { id: 'loren', name: 'minigames.loren.name', tagline: 'minigames.loren.tagline' },
]

async function loadBoard() {
  if (!trs.enabled) {
    board.value = null
    return
  }
  const asked = game.value
  boardLoading.value = true
  boardError.value = false
  try {
    const list = await store.leaderboard(asked)
    if (asked === game.value) board.value = list
  }
  catch {
    if (asked === game.value) {
      board.value = null
      boardError.value = true
    }
  }
  finally {
    boardLoading.value = false
  }
}

async function onFinished(run: FinishedRun) {
  result.value = null
  submitting.value = true
  try {
    const outcome = await store.submit(run)
    result.value = outcome
    if (outcome.kind === 'ranked') await loadBoard()
  }
  finally {
    submitting.value = false
  }
}

function select(id: GameId) {
  if (game.value === id) return
  game.value = id
  result.value = null
  board.value = null
}

function format(value: number) {
  return value.toLocaleString(currentLocale.value)
}

async function confirmRemove() {
  const entry = removing.value
  if (!entry) return
  try {
    await store.remove(game.value, entry.uuid, removeReason.value)
    removing.value = null
    removeReason.value = ''
    await loadBoard()
  }
  catch (error) {
    toasts.error(error)
  }
}

watch(game, loadBoard)
watch(() => trs.enabled, loadBoard)
onMounted(loadBoard)
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-4 p-5">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <h1 class="text-xl font-semibold text-base-50">{{ t('nav.minigames') }}</h1>
      <div class="flex gap-2" role="tablist" :aria-label="t('nav.minigames')">
        <button
          v-for="item in games"
          :key="item.id"
          type="button"
          role="tab"
          class="rounded-lg border px-3 py-2 text-left transition-colors"
          :class="game === item.id ? 'border-redstone-500 bg-redstone-900/25 text-base-50' : 'border-base-700 bg-base-850 text-base-200 hover:border-base-500'"
          :aria-selected="game === item.id"
          @click="select(item.id)"
        >
          <span class="block text-sm font-semibold">{{ t(item.name) }}</span>
        </button>
      </div>
    </header>

    <div class="grid min-h-0 flex-1 gap-4 lg:grid-cols-[minmax(0,1fr)_18rem]">
      <div class="flex min-h-[420px] min-w-0">
        <MinigamesMinigamePlayer :key="game" :game="game" class="w-full" @finished="onFinished">
          <template #result>
            <p v-if="submitting" class="mt-1 text-sm text-base-200">{{ t('minigames.result.checking') }}</p>
            <template v-else-if="result">
              <p v-if="result.kind === 'ranked'" class="mt-1 text-sm text-base-100">
                {{ result.improved ? t('minigames.result.improved', { rank: result.rank }) : t('minigames.result.notImproved', { rank: result.rank, score: format(result.score) }) }}
              </p>
              <p v-else-if="result.kind === 'rejected'" class="mt-1 text-sm text-warn">{{ t('minigames.result.rejected') }}</p>
              <p v-else-if="result.kind === 'failed'" class="mt-1 text-sm text-warn">{{ t('minigames.result.failed') }}</p>
              <p v-else class="mt-1 text-sm text-base-300">{{ t('minigames.result.local') }}</p>
            </template>
          </template>
        </MinigamesMinigamePlayer>
      </div>

      <aside class="flex min-h-0 flex-col rounded-xl border border-base-800 bg-base-850 p-4" :aria-label="t('minigames.board.title')">
        <h2 class="text-sm font-semibold text-base-50">{{ t('minigames.board.title') }}</h2>
        <p v-if="!trs.enabled" class="mt-3 text-sm text-base-300">{{ t('minigames.board.needsTrs') }}</p>
        <p v-else-if="boardError" class="mt-3 text-sm text-base-300">{{ t('minigames.board.error') }}</p>
        <p v-else-if="boardLoading && !board" class="mt-3 text-sm text-base-300">{{ t('common.status.loading') }}</p>
        <p v-else-if="board && !board.entries.length" class="mt-3 text-sm text-base-300">{{ t('minigames.board.empty') }}</p>
        <ol v-else-if="board" class="mt-3 min-h-0 flex-1 space-y-1 overflow-y-auto">
          <li v-for="entry in board.entries" :key="entry.uuid" class="group flex items-center gap-2 rounded px-1.5 py-1 text-sm" :class="{ 'bg-redstone-900/25': entry.rank === board.me?.rank }">
            <span class="w-7 flex-none text-right tabular-nums text-base-300">{{ entry.rank }}</span>
            <span class="min-w-0 flex-1 truncate text-base-100">{{ entry.name }}</span>
            <span class="flex-none tabular-nums font-semibold text-base-50">{{ format(entry.score) }}</span>
            <button v-if="canManage" type="button" class="flex-none text-base-400 opacity-0 hover:text-danger focus:opacity-100 group-hover:opacity-100" :aria-label="t('minigames.board.remove', { name: entry.name })" :title="t('minigames.board.remove', { name: entry.name })" @click="removing = entry">
              <AppIcon name="close" class="size-3.5" />
            </button>
          </li>
        </ol>
        <p v-if="board?.me" class="mt-3 border-t border-base-800 pt-3 text-sm text-base-200">{{ t('minigames.board.me', { rank: board.me.rank, score: format(board.me.score) }) }}</p>
        <p class="mt-3 border-t border-base-800 pt-3 text-xs text-base-400">{{ t('minigames.board.note') }}</p>
      </aside>
    </div>

    <BaseDialog v-if="removing" :title="t('minigames.board.removeTitle', { name: removing.name })" @close="removing = null">
      <label class="block text-sm text-base-200" for="minigame-remove-reason">{{ t('minigames.board.removeReason') }}</label>
      <input id="minigame-remove-reason" v-model="removeReason" class="input mt-2 w-full" maxlength="300" />
      <div class="mt-4 flex justify-end gap-2">
        <button type="button" class="btn-ghost" @click="removing = null">{{ t('common.actions.cancel') }}</button>
        <button type="button" class="btn-danger" :disabled="!removeReason.trim()" @click="confirmRemove">{{ t('minigames.board.removeConfirm') }}</button>
      </div>
    </BaseDialog>
  </div>
</template>
