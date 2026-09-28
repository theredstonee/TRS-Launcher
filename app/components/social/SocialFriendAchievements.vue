<script setup lang="ts">
import { achievementTitle, friendAchievements, isHiddenAchievement } from '~/utils/achievements'
import type { TrsUserRef } from '~/utils/trs'

// Aufgeklappt unter einem Freund in „Sozial“: seine Punkte und die neuesten
// Erfolge (Titel aus dem eigenen Katalog – was ich nicht kenne, bleibt geheim).
// „Alle ansehen“ öffnet die Freundes-Ansicht auf /achievements.
const props = defineProps<{ friend: TrsUserRef }>()

const store = useAchievementsStore()
const loading = ref(true)

const entry = computed(() => store.friends[props.friend.uuid]?.data ?? null)
const latest = computed(() => (entry.value ? friendAchievements(entry.value, store.data, 3) : []))
const error = computed(() => store.friendErrors[props.friend.uuid] ?? null)

onMounted(async () => {
  await Promise.allSettled([store.load(), store.loadFriend(props.friend.uuid)])
  loading.value = false
})

function openAll() {
  void navigateTo({ path: '/achievements', query: { friend: props.friend.uuid, name: props.friend.name } })
}
</script>

<template>
  <div class="mt-2 rounded-md border border-base-700 bg-base-900/60 px-3 py-2.5" data-testid="friend-achievements">
    <div v-if="loading" class="skeleton h-12" />
    <p v-else-if="error || !entry" class="text-xs text-base-400">{{ error ?? t('achievements.friend.unavailable') }}</p>
    <p v-else-if="entry.hidden" class="flex items-center gap-2 text-xs text-base-400" data-testid="friend-achievements-private">
      <SocialIcon name="lock" class="size-3.5" />{{ t('achievements.friend.privateText', { name: friend.name }) }}
    </p>
    <template v-else>
      <div class="flex items-center gap-2">
        <SocialIcon name="trophy" class="size-4 text-lamp-400" />
        <p class="text-xs text-base-200">
          <span class="font-semibold text-base-50">{{ t('achievements.points', { points: entry.points }, entry.points) }}</span>
          · {{ t('achievements.friend.unlockedCount', { n: entry.unlocked.length }, entry.unlocked.length) }}
        </p>
      </div>
      <ul v-if="latest.length" class="mt-2 space-y-1">
        <li v-for="a in latest" :key="a.id" class="flex items-center gap-2 text-xs">
          <span class="grid size-6 shrink-0 place-items-center rounded bg-base-800 text-lamp-300">
            <AchievementIcon :icon="a.achievement && !isHiddenAchievement(a.achievement) ? a.achievement.icon : 'secret'" class="size-3.5" />
          </span>
          <span class="min-w-0 flex-1 truncate text-base-200">{{ achievementTitle(a.achievement) }}</span>
          <span class="shrink-0 text-base-400">{{ trsDate(a.at) }}</span>
        </li>
      </ul>
      <p v-else class="mt-1.5 text-xs text-base-400">{{ t('achievements.friend.none', { name: friend.name }) }}</p>
      <button class="mt-2 text-xs font-medium text-redstone-300 hover:underline" data-testid="friend-achievements-all" @click="openAll">
        {{ t('achievements.friend.viewAll') }}
      </button>
    </template>
  </div>
</template>
