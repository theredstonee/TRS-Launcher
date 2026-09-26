<script setup lang="ts">
import type { PlayerListItem, PlayerQuery } from '~/utils/team'

// Spielerliste: Namenssuche (auch frühere Namen), Filter (bestraft, gesperrt,
// Team, gemeldet), Sortierung. Enter/Klick öffnet die Akte. Unbekannte Namen
// (nie angemeldet) findet die globale Suche per Mojang-UUID nicht – dafür gibt
// es das Feld „UUID öffnen“.
const toasts = useToasts()
const router = useRouter()
const q = ref('')
const status = ref<NonNullable<PlayerQuery['status']>>('all')
const sort = ref<NonNullable<PlayerQuery['sort']>>('last_login')
const players = ref<PlayerListItem[] | null>(null)
const cursor = ref<string | null>(null)
const loadingMore = ref(false)
const qError = ref<string | null>(null)
const items = computed(() => players.value ?? [])

async function load(more = false) {
  const name = q.value.trim()
  if (name && !/^[A-Za-z0-9_]{1,16}$/.test(name)) {
    // UUID eingegeben → direkt zur Akte.
    const plain = name.replace(/-/g, '').toLowerCase()
    if (/^[0-9a-f]{32}$/.test(plain)) {
      void router.push(`/admin/players/${plain}`)
      return
    }
    qError.value = t('team.players.nameRule')
    return
  }
  qError.value = null
  if (more) loadingMore.value = true
  try {
    const page = await backend.team.players({ q: name || undefined, status: status.value, sort: sort.value, cursor: more ? (cursor.value ?? undefined) : undefined, limit: 50 })
    players.value = more ? [...(players.value ?? []), ...page.players] : page.players
    cursor.value = page.nextCursor
  } catch (e) {
    if (!more) players.value = []
    toasts.error(e)
  } finally {
    loadingMore.value = false
  }
}

let timer: ReturnType<typeof setTimeout> | null = null
watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), 300)
})
watch([status, sort], () => void load())
onMounted(() => void load())

const open = (p: PlayerListItem) => void router.push(`/admin/players/${p.uuid}`)
const { active } = useListKeys(items, { open })
</script>

<template>
  <section :aria-label="t('team.nav.players')" data-testid="admin-players">
    <div class="mb-4 flex flex-wrap items-center gap-2">
      <input v-model="q" class="field w-64 py-1.5" maxlength="36" :placeholder="t('team.players.placeholder')" :aria-label="t('team.players.placeholder')" data-testid="players-q" />
      <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs">
        <button
          v-for="s in (['all', 'sanctioned', 'banned', 'reported', 'staff'] as const)"
          :key="s"
          class="seg rounded-md"
          :class="{ 'seg-on': status === s }"
          :aria-pressed="status === s"
          @click="status = s"
        >
          {{ t(`team.players.filters.${s}`) }}
        </button>
      </div>
      <select v-model="sort" class="field ml-auto w-auto py-1.5 text-xs" :aria-label="t('team.common.sort')">
        <option value="last_login">{{ t('team.players.sortLogin') }}</option>
        <option value="created">{{ t('team.players.sortCreated') }}</option>
      </select>
      <p v-if="qError" role="alert" class="w-full text-xs text-redstone-300">{{ qError }}</p>
    </div>

    <div v-if="!players" class="space-y-2"><div v-for="i in 5" :key="i" class="skeleton h-14" /></div>
    <RedstoneEmpty v-else-if="!players.length" :title="t('team.players.none')" compact :seed="0x2c" />
    <div v-else class="card overflow-hidden">
      <table class="w-full text-left text-sm">
        <thead class="text-xs text-base-400">
          <tr class="border-b border-base-800">
            <th class="px-4 py-2 font-medium">{{ t('team.players.player') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.players.sanctions') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.players.reports') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.players.lastLogin') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.players.since') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(p, i) in players"
            :key="p.uuid"
            :data-row="i"
            class="adm-row cursor-pointer border-b border-base-800/60 last:border-0"
            :class="{ 'adm-row-active': active === i }"
            data-testid="player-row"
            @click="open(p)"
          >
            <td class="px-4 py-2">
              <span class="flex items-center gap-2.5">
                <span class="block size-7 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="p.uuid" :name="p.name || '?'" /></span>
                <span class="min-w-0">
                  <span class="flex items-center gap-1.5 font-semibold text-base-50">
                    {{ p.name || p.uuid }}
                    <span v-if="p.online" class="size-2 rounded-full bg-ok" :title="t('common.status.online')" />
                    <span v-if="p.role" class="badge bg-redstone-900/60 text-redstone-300">{{ t(`team.roles.${p.role}`) }}</span>
                  </span>
                  <span class="block font-mono text-[10px] text-base-600">{{ p.uuid }}</span>
                </span>
              </span>
            </td>
            <td class="px-3 py-2">
              <span class="flex flex-wrap gap-1">
                <SanctionKindBadge v-for="k in p.activeSanctions" :key="k" :kind="k" />
                <span v-if="!p.activeSanctions.length" class="text-xs text-base-600">–</span>
              </span>
            </td>
            <td class="px-3 py-2 text-xs tabular-nums" :class="p.openReports ? 'text-lamp-300' : 'text-base-600'">{{ p.openReports || '–' }}</td>
            <td class="px-3 py-2 text-xs text-base-200" :title="formatDate(p.lastLoginAt)">{{ p.lastLoginAt ? formatRelative(p.lastLoginAt) : '–' }}</td>
            <td class="px-3 py-2 text-xs text-base-400">{{ formatShortDate(p.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <button v-if="cursor" class="btn btn-ghost mt-3" :disabled="loadingMore" @click="load(true)">{{ t('admin.mod.more') }}</button>
  </section>
</template>
