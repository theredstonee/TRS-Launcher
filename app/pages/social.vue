<script setup lang="ts">
import type { TrsUserRef } from '~/utils/trs'
import type { ReportTarget } from '~/utils/chat'

// „Sozial“: Chat (Direktnachrichten und Gruppen), Freunde (Liste, Anfragen,
// Blockiert) und Welten (gehostete Welten von Freunden). Alles aktualisiert sich über den Echtzeit-Kanal des Kerns –
// ohne Neuladen. Oben: Freund hinzufügen, Gruppe erstellen, Spieler
// blockieren, eigene Meldungen und Suche.
const trs = useTrsStore()
const chat = useChatStore()
const live = useLiveStore()
const accounts = useAccountsStore()
const route = useRoute()
const router = useRouter()
const toasts = useToasts()
const hosting = useHostingStore()

type Tab = 'chat' | 'friends' | 'worlds'
function tabFromQuery(value: unknown): Tab | null {
  return value === 'friends' || value === 'worlds' ? value : null
}
const tab = ref<Tab>(tabFromQuery(route.query.tab) ?? 'chat')
const search = ref('')
const searchOpen = ref(false)
const searchInput = useTemplateRef<HTMLInputElement>('searchInput')
const dialog = ref<'add' | 'block' | 'group' | 'reports' | null>(null)
const managing = ref<string | null>(null)
const reporting = ref<{ target: ReportTarget; label: string } | null>(null)

const requestBadge = computed(() => trs.incomingCount)

// --- Unterhaltung aus der Adresse öffnen (?c=…, ?dm=…, ?tab=…) -------------------------------

async function openFromRoute() {
  const c = typeof route.query.c === 'string' ? route.query.c : null
  const dm = typeof route.query.dm === 'string' ? route.query.dm : null
  const fromQuery = tabFromQuery(route.query.tab)
  if (fromQuery) tab.value = fromQuery
  if (c && /^c[0-9a-f]{20}$/.test(c)) {
    tab.value = 'chat'
    chat.activeId = c
  } else if (dm) {
    tab.value = 'chat'
    await openFriend(dm)
  }
}

async function openFriend(uuid: string) {
  try {
    const id = await chat.openDm(uuid)
    tab.value = 'chat'
    chat.activeId = id
  } catch (e) {
    toasts.error(e)
  }
}

function openConversation(id: string) {
  chat.activeId = id
  if (route.query.c !== id) void router.replace({ query: { c: id } })
}

watch(() => route.query, () => void openFromRoute())
// Account (und damit der Chat-Zustand) kam erst nach dem Öffnen der Seite: Ziel erneut öffnen.
watch(
  () => chat.me,
  (me) => {
    if (me) void openFromRoute()
  },
)

// --- Sichtbarkeit: Neues zählt sofort als gelesen, solange man hinschaut ----------------------

function updateWatching() {
  chat.setWatching(tab.value === 'chat' && document.visibilityState === 'visible' && document.hasFocus())
}
watch(tab, (value) => {
  updateWatching()
  if (value === 'friends' && !trs.blocked) void trs.loadBlocked()
  if (route.query.tab !== value && value !== 'chat') void router.replace({ query: { tab: value } })
})

onMounted(async () => {
  window.addEventListener('focus', updateWatching)
  window.addEventListener('blur', updateWatching)
  document.addEventListener('visibilitychange', updateWatching)
  updateWatching()
  if (!trs.status) await trs.refreshStatus()
  if (trs.enabled && chat.me && chat.listState === 'idle') void chat.loadList().catch(() => {})
  await openFromRoute()
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', updateWatching)
  window.removeEventListener('blur', updateWatching)
  document.removeEventListener('visibilitychange', updateWatching)
  chat.setWatching(false)
  chat.close()
})

watch(
  () => [trs.enabled, accounts.active?.id],
  () => {
    search.value = ''
  },
)

function toggleSearch() {
  searchOpen.value = !searchOpen.value
  if (!searchOpen.value) search.value = ''
  else void nextTick(() => searchInput.value?.focus())
}

function reportPlayer(friend: TrsUserRef) {
  reporting.value = { target: { kind: 'player', uuid: friend.uuid }, label: friend.name }
}

const retryIn = computed(() => Math.ceil((live.status.retryInMs ?? 0) / 1000))
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-3 p-4" data-testid="social-page">
    <!-- Kopf: Titel, Reiter, Aktionen -->
    <header class="card flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5">
      <h1 class="display text-2xl leading-none text-base-50">{{ t('social.title') }}</h1>
      <div class="flex gap-1" role="tablist" :aria-label="t('social.tabs.label')">
        <button class="tab flex items-center gap-1.5" :class="{ 'tab-on': tab === 'chat' }" role="tab" :aria-selected="tab === 'chat'" data-testid="tab-chat" @click="tab = 'chat'">
          {{ t('social.tabs.chat') }}
          <span v-if="chat.unreadTotal" class="rounded-full bg-redstone-500 px-1.5 text-[10px] font-bold text-white">{{ chat.unreadTotal > 99 ? '99+' : chat.unreadTotal }}</span>
        </button>
        <button class="tab flex items-center gap-1.5" :class="{ 'tab-on': tab === 'friends' }" role="tab" :aria-selected="tab === 'friends'" data-testid="tab-friends" @click="tab = 'friends'">
          {{ t('social.tabs.friends') }}
          <span v-if="requestBadge" class="rounded-full bg-redstone-500 px-1.5 text-[10px] font-bold text-white">{{ requestBadge }}</span>
        </button>
        <button class="tab flex items-center gap-1.5" :class="{ 'tab-on': tab === 'worlds' }" role="tab" :aria-selected="tab === 'worlds'" data-testid="tab-worlds" @click="tab = 'worlds'">
          {{ t('social.tabs.worlds') }}
          <span v-if="hosting.invitedCount" class="rounded-full bg-redstone-500 px-1.5 text-[10px] font-bold text-white">{{ hosting.invitedCount }}</span>
        </button>
      </div>
      <span
        v-if="trs.enabled"
        class="flex items-center gap-1.5 text-[11px] text-base-400"
        :title="t(`social.live.${live.status.state}`)"
        data-testid="live-state"
      >
        <span class="size-2 rounded-full" :class="live.connected ? 'bg-ok' : live.status.state === 'connecting' ? 'bg-lamp-400 animate-pulse' : 'bg-base-600'" />
        <span class="hidden xl:inline">{{ live.status.state === 'down' ? t('common.status.offline') : t(`social.live.${live.status.state}`) }}</span>
      </span>

      <div class="ml-auto flex items-center gap-1.5">
        <div v-if="searchOpen" class="relative">
          <SocialIcon name="search" class="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-base-400" />
          <input
            ref="searchInput"
            v-model="search"
            class="field w-56 py-1.5 pr-8 pl-8"
            :placeholder="t('social.actions.searchPlaceholder')"
            :aria-label="t('social.actions.search')"
            data-testid="social-search"
            @keydown.esc="toggleSearch"
          />
          <button class="absolute top-1/2 right-2 -translate-y-1/2 text-base-400 hover:text-base-50" :aria-label="t('social.actions.clearSearch')" @click="toggleSearch">
            <SocialIcon name="close" class="size-3.5" />
          </button>
        </div>
        <button class="btn-icon bg-redstone-500 text-white hover:bg-redstone-400" :disabled="!trs.enabled" :title="t('social.actions.addFriend')" :aria-label="t('social.actions.addFriend')" data-testid="social-add" @click="dialog = 'add'">
          <SocialIcon name="userPlus" class="size-4.5" />
        </button>
        <button class="btn-icon" :disabled="!trs.enabled || !chat.me" :title="t('social.actions.makeGroup')" :aria-label="t('social.actions.makeGroup')" data-testid="social-group" @click="dialog = 'group'">
          <SocialIcon name="groupAdd" class="size-4.5" />
        </button>
        <button class="btn-icon" :disabled="!trs.enabled" :title="t('social.actions.blockPlayer')" :aria-label="t('social.actions.blockPlayer')" data-testid="social-block" @click="dialog = 'block'">
          <SocialIcon name="block" class="size-4.5" />
        </button>
        <button class="btn-icon" :disabled="!trs.enabled || !chat.me" :title="t('social.reports.title')" :aria-label="t('social.reports.title')" data-testid="social-reports" @click="dialog = 'reports'">
          <SocialIcon name="flag" class="size-4.5" />
        </button>
        <button v-if="!searchOpen" class="btn-icon" :title="t('social.actions.search')" :aria-label="t('social.actions.search')" data-testid="social-search-toggle" @click="toggleSearch">
          <SocialIcon name="search" class="size-4.5" />
        </button>
      </div>
    </header>

    <TrsGate what="friends">
      <!-- Verbindung unterbrochen: Rückfall läuft, Hinweis mit „Jetzt verbinden“ -->
      <div v-if="live.status.state === 'down'" class="card flex items-center gap-3 px-4 py-2 text-xs text-base-400" role="status" data-testid="live-down">
        <span class="size-2 rounded-full bg-base-600" />
        <span class="flex-1">{{ retryIn > 0 ? t('social.live.retrying', { seconds: retryIn }) : t('social.live.down') }}</span>
        <button class="btn btn-ghost px-3 py-1 text-xs" @click="live.reconnect()">{{ t('social.live.reconnect') }}</button>
      </div>
      <div v-if="chat.chatMuted" class="card flex items-center gap-3 border-lamp-900 px-4 py-2 text-xs text-lamp-300" role="status">
        <SocialIcon name="bellOff" class="size-4" />
        {{ chat.moderation?.mute?.until ? t('social.moderation.mutedUntil', { date: dateTime(chat.moderation.mute.until) }) : t('social.moderation.mutedReview') }}
      </div>

      <!-- Chat -->
      <div v-if="tab === 'chat'" class="card flex min-h-0 flex-1 overflow-hidden" data-testid="chat-tab">
        <aside class="flex w-80 shrink-0 flex-col border-r border-base-800">
          <SocialChatList :search="search" class="min-h-0 flex-1" @open="openConversation" @open-friend="openFriend" />
        </aside>
        <SocialConversation
          v-if="chat.activeId && chat.conversations[chat.activeId]"
          :key="chat.activeId"
          :conversation-id="chat.activeId"
          @manage-group="managing = chat.activeId"
          @removed="chat.activeId = null"
        />
        <div v-else class="grid min-w-0 flex-1 place-items-center p-8">
          <RedstoneEmpty :title="t('social.chat.selectTitle')" :text="t('social.chat.selectText')" :seed="0x5c" compact>
            <button class="btn btn-primary" @click="dialog = 'add'">{{ t('social.actions.addFriend') }}</button>
          </RedstoneEmpty>
        </div>
      </div>

      <!-- Freunde -->
      <SocialFriendsPanel v-else-if="tab === 'friends'" :search="search" @message="openFriend" @report="reportPlayer" />

      <!-- Welten (gehostete Welten von Freunden) -->
      <SocialWorldsPanel v-else :search="search" />
    </TrsGate>

    <SocialPlayerDialog v-if="dialog === 'add' || dialog === 'block'" :mode="dialog" @close="dialog = null" />
    <SocialGroupDialog v-if="dialog === 'group'" @close="dialog = null" @created="(id) => { tab = 'chat'; openConversation(id) }" />
    <SocialGroupDialog v-if="managing" :conversation-id="managing" @close="managing = null" />
    <SocialMyReportsDialog v-if="dialog === 'reports'" @close="dialog = null" />
    <SocialReportDialog v-if="reporting" :target="reporting.target" :label="reporting.label" @close="reporting = null" />
  </div>
</template>
