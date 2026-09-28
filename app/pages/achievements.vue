<script setup lang="ts">
import {
  achievementDescription,
  achievementFilters,
  achievementTitle,
  achievementCategoryLabel,
  groupAchievements,
  isHiddenAchievement,
  achievementRarityLabel,
  achievementRewardAction,
  achievementRewardLabel,
  achievementProgressText,
  summarizeAchievements,
  type AchievementFilter,
  type AchievementItem,
  type MyAchievements,
} from '~/utils/achievements'

// Launcher-Erfolge: nach Kategorie gruppiert, mit Punkten, Seltenheit,
// Fortschritt, Datum und Belohnung. `?friend=<uuid>&name=<name>` zeigt die
// Erfolge eines Freundes (mit dem eigenen Katalog), `?highlight=<id>` springt
// zu einem Erfolg (z. B. aus der Benachrichtigung).
const route = useRoute()
const store = useAchievementsStore()
const trs = useTrsStore()
const accounts = useAccountsStore()

const filter = ref<AchievementFilter>('all')

const friendUuid = computed(() => {
  const q = route.query.friend
  return typeof q === 'string' && /^[0-9a-f]{32}$/.test(q) ? q : null
})
const friendName = computed(() => {
  const q = route.query.name
  return typeof q === 'string' && /^\w{1,16}$/.test(q) ? q : '?'
})
const highlightId = computed(() => (typeof route.query.highlight === 'string' ? route.query.highlight : null) ?? store.highlight)

const friend = computed(() => (friendUuid.value ? store.friends[friendUuid.value]?.data ?? null : null))
const friendError = computed(() => (friendUuid.value ? store.friendErrors[friendUuid.value] ?? null : null))
const friendLoading = ref(false)

/** Freundes-Ansicht: mein Katalog, seine freigeschalteten Erfolge (ohne Fortschritt). */
const shown = computed<MyAchievements | null>(() => {
  const mine = store.data
  if (!mine) return null
  if (!friendUuid.value) return mine
  const f = friend.value
  if (!f) return null
  const known = new Set(mine.achievements.map((a) => a.id))
  return { achievements: mine.achievements, unlocked: f.unlocked.filter((u) => known.has(u.id)), progress: {}, points: f.points, totalPoints: mine.totalPoints, visibleToFriends: true }
})
const groups = computed(() => (shown.value ? groupAchievements(shown.value, filter.value) : []))
const summary = computed(() => (shown.value ? summarizeAchievements(shown.value) : null))
const mineUnlocked = computed(() => new Set(store.data?.unlocked.map((u) => u.id) ?? []))
const overall = computed(() => (summary.value && summary.value.total ? Math.round((summary.value.unlocked / summary.value.total) * 100) : 0))

// --- Belohnungen: „Jetzt tragen“ nur, wenn der Umhang/Kopf wirklich in meiner Liste ist ---------------

/** Eigene Umhänge/Kopf-Kosmetik (und deren Namen) – nur wenn eine Belohnung sie braucht. */
async function loadOwned() {
  const data = store.data
  if (!data) return
  const rewards = data.achievements.flatMap((a) => (a.reward ? [a.reward] : []))
  if (!rewards.length) return
  await store.loadRewards({ capes: rewards.some((r) => r.kind === 'cape'), hats: rewards.some((r) => r.kind === 'cosmetic') })
}

function actionFor(item: AchievementItem) {
  return !friendUuid.value && item.unlocked ? achievementRewardAction(item.achievement.reward, store.owned) : null
}

const wearing = ref<string | null>(null)
async function wear(item: AchievementItem) {
  const reward = item.achievement.reward
  const action = actionFor(item)
  if (!reward || !action) return
  wearing.value = item.achievement.id
  await store.wear(reward, action)
  wearing.value = null
}

// --- Laden ------------------------------------------------------------------------------------

async function load(force = false) {
  await store.load(force)
  if (friendUuid.value) {
    friendLoading.value = true
    await store.loadFriend(friendUuid.value, force)
    friendLoading.value = false
  } else {
    void loadOwned()
  }
}

onMounted(() => void load())
watch(() => [trs.enabled, accounts.active?.id, friendUuid.value], () => void load())
// Neu freigeschaltet (Live-Ereignis) → Belohnungen neu prüfen.
watch(() => store.data?.unlocked.length, () => void loadOwned())

// Zum hervorgehobenen Erfolg springen, sobald er da ist.
watch(
  () => [highlightId.value, groups.value.length] as const,
  async ([id]) => {
    if (!id) return
    await nextTick()
    document.querySelector(`[data-achievement="${CSS.escape(id)}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  },
  { immediate: true },
)

function progressText(item: AchievementItem): string {
  return achievementProgressText(item.progress ?? 0, item.achievement.goal ?? 0, item.achievement.unit)
}
</script>

<template>
  <div class="mx-auto max-w-5xl p-6" data-testid="achievements-page">
    <PageHeader
      :title="friendUuid ? t('achievements.friend.title', { name: friendName }) : t('achievements.title')"
      :subtitle="friendUuid ? t('achievements.friend.subtitle') : t('achievements.subtitle')"
    >
      <NuxtLink v-if="friendUuid" to="/social?tab=friends" class="btn btn-ghost">
        <SocialIcon name="reply" class="size-4" />{{ t('achievements.friend.back') }}
      </NuxtLink>
      <button class="btn btn-ghost" :disabled="store.loading || friendLoading || !trs.enabled" data-testid="achievements-refresh" @click="load(true)">
        <SocialIcon name="sync" class="size-4" :class="{ 'animate-spin': store.loading || friendLoading }" />{{ t('achievements.refresh') }}
      </button>
    </PageHeader>

    <TrsGate what="achievements">
      <!-- Laden / Fehler -->
      <div v-if="(store.loading && !store.data) || (friendUuid && friendLoading && !friend)" class="space-y-3">
        <div class="skeleton h-24" />
        <div class="grid gap-3 sm:grid-cols-2">
          <div v-for="i in 4" :key="i" class="skeleton h-28" />
        </div>
      </div>
      <div v-else-if="store.error && !store.data" class="card px-4 py-5 text-sm" role="alert">
        <p class="font-semibold text-base-50">{{ t('achievements.error') }}</p>
        <p class="mt-0.5 text-base-400">{{ store.error }}</p>
        <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="load(true)">{{ t('common.actions.retry') }}</button>
      </div>
      <div v-else-if="friendUuid && friend?.hidden" class="card flex items-center gap-3 px-4 py-5 text-sm" data-testid="achievements-private">
        <SocialIcon name="lock" class="size-5 shrink-0 text-base-400" />
        <div>
          <p class="font-semibold text-base-50">{{ t('achievements.friend.private') }}</p>
          <p class="mt-0.5 text-base-400">{{ t('achievements.friend.privateText', { name: friendName }) }}</p>
        </div>
      </div>
      <div v-else-if="friendUuid && !friend" class="card px-4 py-5 text-sm" role="alert">
        <p class="font-semibold text-base-50">{{ t('achievements.friend.unavailable') }}</p>
        <p v-if="friendError" class="mt-0.5 text-base-400">{{ friendError }}</p>
      </div>

      <template v-else-if="shown && summary">
        <!-- Übersicht -->
        <section class="card mb-5 flex flex-wrap items-center gap-x-6 gap-y-3 px-5 py-4" data-testid="achievements-summary">
          <span v-if="friendUuid" class="block size-12 shrink-0 overflow-hidden rounded-lg"><PlayerFace :uuid="friendUuid" :name="friendName" /></span>
          <span v-else class="grid size-12 shrink-0 place-items-center rounded-lg bg-lamp-900 text-lamp-300">
            <SocialIcon name="trophy" class="size-6" />
          </span>
          <div>
            <p class="display text-3xl leading-none text-base-50" data-testid="achievements-points">{{ formatNumber(summary.points) }}</p>
            <p class="mt-1 text-xs text-base-400">{{ t('achievements.pointsOf', { max: formatNumber(summary.maxPoints) }) }}</p>
          </div>
          <div class="min-w-48 flex-1">
            <div class="flex items-baseline justify-between text-xs">
              <span class="text-base-200">{{ t('achievements.unlockedOf', { n: summary.unlocked, total: summary.total }) }}</span>
              <span class="text-base-400">{{ overall }} %</span>
            </div>
            <div
              class="mt-1.5 h-2 overflow-hidden rounded-full bg-base-800"
              role="progressbar"
              :aria-valuenow="overall"
              aria-valuemin="0"
              aria-valuemax="100"
              :aria-label="t('achievements.overall')"
            >
              <div class="h-full rounded-full bg-lamp-400 transition-[width] duration-500" :style="{ width: `${overall}%` }" />
            </div>
          </div>
          <div class="flex gap-1" role="radiogroup" :aria-label="t('achievements.filter.label')">
            <button
              v-for="f in achievementFilters"
              :key="f"
              class="tab"
              :class="{ 'tab-on': filter === f }"
              role="radio"
              :aria-checked="filter === f"
              :data-testid="`achievements-filter-${f}`"
              @click="filter = f"
            >
              {{ t(`achievements.filter.${f}`) }}
            </button>
          </div>
        </section>

        <div v-if="!friendUuid" class="card mb-5 px-4 py-1">
          <AchievementsVisibility />
        </div>

        <p v-if="!summary.total" class="card px-4 py-8 text-center text-sm text-base-400">{{ t('achievements.empty') }}</p>
        <p v-else-if="!groups.length" class="card px-4 py-8 text-center text-sm text-base-400">{{ t('achievements.emptyFilter') }}</p>

        <!-- Kategorien -->
        <section v-for="g in groups" :key="g.category" class="mb-6" :data-testid="`achievements-group-${g.category}`">
          <h2 class="heading mb-2.5 flex items-baseline gap-2 text-base text-base-50">
            {{ achievementCategoryLabel(g.category) }}
            <span class="text-xs font-normal text-base-400">{{ g.unlocked }}/{{ g.total }}</span>
          </h2>
          <div class="grid gap-3 sm:grid-cols-2">
            <article
              v-for="item in g.items"
              :key="item.achievement.id"
              class="card achievement flex gap-3.5 p-3.5"
              :class="[`rarity-${item.achievement.rarity}`, { locked: !item.unlocked, highlighted: highlightId === item.achievement.id }]"
              :data-achievement="item.achievement.id"
              :data-testid="item.unlocked ? 'achievement-unlocked' : 'achievement-locked'"
            >
              <span class="achievement-icon grid size-12 shrink-0 place-items-center rounded-lg text-2xl">
                <AchievementIcon :icon="isHiddenAchievement(item.achievement) ? 'secret' : item.achievement.icon" class="size-6" />
              </span>
              <div class="min-w-0 flex-1">
                <div class="flex items-start gap-2">
                  <h3 class="min-w-0 flex-1 text-sm font-semibold text-base-50" :class="{ 'text-base-400 italic': isHiddenAchievement(item.achievement) }">
                    {{ achievementTitle(item.achievement) }}
                  </h3>
                  <span class="shrink-0 text-xs font-semibold text-lamp-300" :title="t('achievements.pointsTitle')">+{{ item.achievement.points }}</span>
                </div>
                <p class="mt-0.5 text-xs text-base-400">{{ achievementDescription(item.achievement) }}</p>

                <div class="mt-2 flex flex-wrap items-center gap-1.5">
                  <span class="badge rarity-chip">{{ achievementRarityLabel(item.achievement.rarity) }}</span>
                  <span v-if="item.achievement.secret && item.achievement.category !== 'secret'" class="badge bg-base-800 text-base-200">{{ t('achievements.secret.badge') }}</span>
                  <span v-if="item.achievement.reward" class="badge bg-redstone-900/50 text-redstone-300" data-testid="achievement-reward">
                    <SocialIcon :name="item.achievement.reward.kind === 'cape' ? 'skins' : 'crown'" class="size-3" />
                    {{ achievementRewardLabel(item.achievement.reward, store.rewardNames) }}
                  </span>
                  <span v-if="friendUuid && item.unlocked && mineUnlocked.has(item.achievement.id)" class="badge bg-base-800 text-ok">{{ t('achievements.friend.youToo') }}</span>
                </div>

                <p v-if="item.unlocked" class="mt-2 flex items-center gap-1 text-[11px] text-ok">
                  <SocialIcon name="check" class="size-3" />
                  {{ item.at ? t('achievements.unlockedAt', { date: trsDate(item.at) }) : t('achievements.unlocked') }}
                </p>
                <div v-else-if="!friendUuid && item.achievement.goal && item.progress !== null" class="mt-2">
                  <div class="flex justify-between text-[11px] text-base-400">
                    <span>{{ progressText(item) }}</span>
                    <span>{{ Math.floor(item.ratio * 100) }} %</span>
                  </div>
                  <div
                    class="mt-1 h-1.5 overflow-hidden rounded-full bg-base-800"
                    role="progressbar"
                    :aria-valuenow="item.progress"
                    aria-valuemin="0"
                    :aria-valuemax="item.achievement.goal"
                    :aria-label="achievementTitle(item.achievement)"
                  >
                    <div class="progress-fill h-full rounded-full" :style="{ width: `${item.ratio * 100}%` }" />
                  </div>
                </div>

                <button
                  v-if="actionFor(item)"
                  class="btn btn-ghost mt-2 px-2.5 py-1 text-xs"
                  :disabled="wearing !== null"
                  data-testid="achievement-wear"
                  @click="wear(item)"
                >
                  {{ actionFor(item) === 'emote' ? t('achievements.reward.whereEmote') : t('achievements.reward.wearNow') }}
                </button>
              </div>
            </article>
          </div>
        </section>
      </template>
    </TrsGate>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

/* Seltenheit: eine Farbe je Stufe (auch im hellen Theme gut lesbar). */
.achievement {
  --rarity: var(--color-base-400);
}
.rarity-uncommon {
  --rarity: var(--color-ok);
}
.rarity-rare {
  --rarity: #6b95ff;
}
.rarity-epic {
  --rarity: #c38cfb;
}
.rarity-legendary {
  --rarity: var(--color-lamp-400);
}
:root[data-theme='light'] .rarity-uncommon {
  --rarity: #15803d;
}
:root[data-theme='light'] .rarity-rare {
  --rarity: #2447b8;
}
:root[data-theme='light'] .rarity-epic {
  --rarity: #7a2fc0;
}
:root[data-theme='light'] .rarity-legendary {
  --rarity: #9a5a00;
}

.achievement-icon {
  color: var(--rarity);
  background: color-mix(in srgb, var(--rarity) 14%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--rarity) 45%, transparent);
}
.rarity-chip {
  color: var(--rarity);
  background: color-mix(in srgb, var(--rarity) 14%, transparent);
}
.progress-fill {
  background: var(--rarity);
}

/* Gesperrt: gedämpft, Symbol grau. */
.locked .achievement-icon {
  @apply text-base-600;
  background: var(--color-base-850);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
.locked h3 {
  @apply text-base-200;
}

.highlighted {
  @apply border-lamp-400;
  box-shadow: 0 0 0 1px var(--color-lamp-400), 0 0 18px -6px var(--color-lamp-400);
}
</style>
