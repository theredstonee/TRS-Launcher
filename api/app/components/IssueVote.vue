<script setup lang="ts">
// Hoch-/Runter-Stimme (§28): je Konto eine Stimme, erneutes Klicken nimmt sie zurück. Ohne Anmeldung führen die
// Pfeile zur Microsoft-Anmeldung. Die Zahl ist der Score (hoch − runter).
const props = withDefaults(defineProps<{
  number: number
  score: number
  myVote?: -1 | 0 | 1
  up?: number
  down?: number
  closed?: boolean
  signedIn: boolean
  size?: 'sm' | 'lg'
}>(), { myVote: 0, up: 0, down: 0, closed: false, size: 'sm' })
const emit = defineEmits<{ voted: [r: { score: number, up: number, down: number, myVote: -1 | 0 | 1 }], error: [message: string] }>()

const { it, fill, errorText } = useIssueText()
const { api, loginUrl } = useAccount()
const busy = ref(false)

async function vote(dir: -1 | 1) {
  if (busy.value || props.closed) return
  if (!props.signedIn) {
    window.location.href = loginUrl()
    return
  }
  const next = props.myVote === dir ? 0 : dir
  busy.value = true
  try {
    const r = await api<{ score: number, up: number, down: number, myVote: -1 | 0 | 1 }>(`/v1/issues/${props.number}/vote`, { method: 'POST', body: { vote: next } })
    emit('voted', r)
  } catch (e) {
    emit('error', errorText(e))
  } finally {
    busy.value = false
  }
}
const label = (dir: -1 | 1) => {
  if (!props.signedIn) return it.value.common.signInToVote
  if (props.myVote === dir) return it.value.common.removeVote
  return dir === 1 ? it.value.common.upvote : it.value.common.downvote
}
</script>

<template>
  <div class="vote" :class="[size, { closed }]" :title="fill(it.detail.sidebar.votes, { up, down })">
    <button
      type="button"
      class="arrow"
      :aria-pressed="myVote === 1"
      :aria-label="label(1)"
      :title="label(1)"
      :disabled="busy || closed"
      data-testid="vote-up"
      @click.prevent.stop="vote(1)"
    >
      <SiteIcon name="voteUp" class="size-full" />
    </button>
    <span class="score tabular-nums" :data-v="myVote" :aria-label="fill(it.common.score, { n: score })">{{ score }}</span>
    <button
      type="button"
      class="arrow down"
      :aria-pressed="myVote === -1"
      :aria-label="label(-1)"
      :title="label(-1)"
      :disabled="busy || closed"
      data-testid="vote-down"
      @click.prevent.stop="vote(-1)"
    >
      <SiteIcon name="voteDown" class="size-full" />
    </button>
  </div>
</template>

<style scoped>
.vote {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.1rem;
  min-width: 2.75rem;
}
.arrow {
  display: grid;
  place-items: center;
  width: 1.9rem;
  height: 1.6rem;
  padding: 0.2rem;
  border-radius: 0.35rem;
  color: var(--color-base-400);
  transition: color 0.12s, background-color 0.12s, transform 0.08s;
}
.arrow:not(:disabled):hover {
  color: var(--color-base-50);
  background: var(--color-base-800);
}
.arrow:not(:disabled):active {
  transform: scale(0.92);
}
.arrow[aria-pressed='true'] {
  color: var(--color-redstone-400);
  filter: drop-shadow(0 0 5px color-mix(in srgb, var(--color-redstone-500) 70%, transparent));
}
.arrow.down[aria-pressed='true'] {
  color: #7cc4ff;
  filter: drop-shadow(0 0 5px color-mix(in srgb, #7cc4ff 55%, transparent));
}
.arrow:disabled {
  cursor: default;
  opacity: 0.45;
}
.score {
  font-family: var(--font-display);
  font-size: 1.15rem;
  line-height: 1;
  color: var(--color-base-50);
}
.score[data-v='1'] {
  color: var(--color-redstone-300);
}
.score[data-v='-1'] {
  color: #9fd4ff;
}
.lg .arrow {
  width: 2.4rem;
  height: 2rem;
}
.lg .score {
  font-size: 1.9rem;
}
</style>
