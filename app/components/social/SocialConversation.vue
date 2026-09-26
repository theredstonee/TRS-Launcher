<script setup lang="ts">
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { isTauri } from '@tauri-apps/api/core'
import type { DropEvent } from '~/types'
import {
  MAX_IMAGES,
  attachmentUrl,
  buildTimeline,
  conversationTitle,
  isMuted,
  localImageUrl,
  readState,
  typingNames,
  typingText,
  type ChatMessage,
  type LocalMessage,
  type ReactionId,
  type ReportTarget,
} from '~/utils/chat'
import type { DraftImage } from '~/stores/chat'

// Eine Unterhaltung: Kopf mit Gesicht/Name und Menü, Verlauf mit Tagestrennern,
// „schreibt …“, Lesestatus, Bildauswahl und Eingabe. Neues kommt über den
// Echtzeit-Kanal – nichts muss neu geladen werden.
const props = defineProps<{ conversationId: string }>()
const emit = defineEmits<{ manageGroup: []; removed: [] }>()

const chat = useChatStore()
const trs = useTrsStore()
const toasts = useToasts()

const conversation = computed(() => chat.conversations[props.conversationId] ?? null)
const thread = computed(() => chat.threads[props.conversationId] ?? null)
const items = computed(() => (thread.value ? buildTimeline(thread.value.items, chat.me) : []))
const title = computed(() => (conversation.value ? conversationTitle(conversation.value, chat.me) : ''))
const peerPresence = computed(() => {
  const c = conversation.value
  if (c?.kind !== 'dm' || !c.peer) return null
  return trs.friends?.friends.find((f) => f.uuid === c.peer!.uuid) ?? null
})
const isOwner = computed(() => conversation.value?.kind === 'group' && conversation.value.owner === chat.me)

// --- „schreibt …“ (läuft nach 8 s von selbst ab) --------------------------------------

const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  clock = setInterval(() => (now.value = Date.now()), 1000)
})
const typers = computed(() =>
  conversation.value ? typingNames(chat.typing[props.conversationId], conversation.value, chat.me, now.value) : [],
)

/** Lesestatus nur unter meiner letzten bestätigten Nachricht. */
const lastMineId = computed(() => {
  const list = thread.value?.items ?? []
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]!
    if (m.local) continue
    if (m.sender?.uuid === chat.me && m.kind === 'text') return m.id
    if (m.sender?.uuid !== chat.me) return null
  }
  return null
})
function receiptFor(m: LocalMessage) {
  if (m.id !== lastMineId.value || !conversation.value) return null
  const shared = trs.me?.settings.chatReadReceipts !== false
  return shared ? readState(conversation.value, m, chat.me) : null
}

// --- Scrollen -------------------------------------------------------------------------

const scroller = useTemplateRef<HTMLElement>('scroller')
const sentinel = useTemplateRef<HTMLElement>('sentinel')
const atBottom = ref(true)
const unseen = ref(0)
const highlight = ref<string | null>(null)

function nearBottom(): boolean {
  const el = scroller.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function toBottom(smooth = false) {
  const el = scroller.value
  if (!el) return
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  unseen.value = 0
  atBottom.value = true
}

function onScroll() {
  atBottom.value = nearBottom()
  if (atBottom.value) {
    unseen.value = 0
    chat.markReadSoon(props.conversationId)
  }
}

// Neue Nachrichten: unten bleiben, wenn man schon unten war; sonst Hinweis.
watch(
  () => thread.value?.items.length ?? 0,
  async (len, before) => {
    const wasBottom = atBottom.value
    const lastItem = thread.value?.items[len - 1]
    await nextTick()
    if (!before) return toBottom()
    if (len > before && (wasBottom || lastItem?.sender?.uuid === chat.me)) toBottom(true)
    else if (len > before) unseen.value += len - before
  },
)

let older: IntersectionObserver | null = null
async function loadOlder() {
  const el = scroller.value
  if (!el || !thread.value?.hasMore || thread.value.loading) return
  const before = el.scrollHeight
  await chat.loadOlder(props.conversationId).catch(() => {})
  await nextTick()
  el.scrollTop += el.scrollHeight - before
}

async function jump(messageId: string) {
  const find = () => scroller.value?.querySelector<HTMLElement>(`[data-message-id="${messageId}"]`)
  let el = find()
  for (let i = 0; i < 5 && !el && thread.value?.hasMore; i++) {
    await loadOlder()
    el = find()
  }
  if (!el) return
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  highlight.value = messageId
  setTimeout(() => {
    if (highlight.value === messageId) highlight.value = null
  }, 1600)
}

// --- Eingabe, Antworten, Bearbeiten, Bilder -----------------------------------------------

const replyTo = ref<ChatMessage | null>(null)
const editing = ref<ChatMessage | null>(null)
const images = ref<DraftImage[]>([])
const pickerOpen = ref(false)
const composer = useTemplateRef<{ focus: () => void }>('composer')
const inviting = ref(false)

function editLast() {
  const list = thread.value?.items ?? []
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]!
    if (!m.local && m.sender?.uuid === chat.me && m.kind === 'text' && !m.deleted && m.text) {
      editing.value = m
      return
    }
  }
}

function addImages(list: DraftImage[]) {
  const room = MAX_IMAGES - images.value.length
  if (list.length > room) toasts.info(t('social.picker.limit', { max: MAX_IMAGES }))
  images.value = [...images.value, ...list.slice(0, Math.max(0, room))]
}

// Bilder ins Fenster ziehen → gleich an die Nachricht hängen (der Kern prüft sie).
const dragging = ref(false)
let unlistenDrop: UnlistenFn | null = null
onMounted(async () => {
  if (!isTauri()) return
  unlistenDrop = await listen<DropEvent>('file-drop', async ({ payload }) => {
    if (!conversation.value?.canWrite || chat.chatMuted) return
    if (payload.type === 'enter') dragging.value = true
    else if (payload.type === 'leave') dragging.value = false
    else {
      dragging.value = false
      try {
        const staged = await backend.social.stageDropped(payload.token)
        addImages(staged.images.map((i) => ({ source: { kind: 'local' as const, id: i.id }, preview: localImageUrl(i.id) })))
        if (staged.rejected) toasts.info(t('social.picker.rejected', staged.rejected))
      } catch (e) {
        toasts.error(e)
      }
    }
  })
})

// --- Bilder groß ansehen ----------------------------------------------------------------------

const lightbox = ref<{ message: ChatMessage; index: number } | null>(null)
function openImage(m: LocalMessage, index: number) {
  if (m.local) return
  lightbox.value = { message: m, index }
}

// --- Kontextmenü ------------------------------------------------------------------------------

const menu = ref<{ message: LocalMessage; x: number; y: number } | null>(null)
function openMenu(m: LocalMessage, e: MouseEvent) {
  if (m.local || m.kind === 'system') return
  menu.value = { message: m, x: Math.min(e.clientX, window.innerWidth - 220), y: Math.min(e.clientY, window.innerHeight - 260) }
}
function closeMenu() {
  menu.value = null
}
const menuMine = computed(() => menu.value?.message.sender?.uuid === chat.me)
const menuCanDelete = computed(() => menuMine.value || isOwner.value)

async function copy(m: ChatMessage) {
  try {
    await navigator.clipboard.writeText(m.text ?? m.invite?.address ?? '')
    toasts.ok(t('social.message.copied'))
  } catch (e) {
    toasts.error(e)
  }
}

async function run(action: () => Promise<unknown>) {
  try {
    await action()
  } catch (e) {
    toasts.error(e)
  }
}

async function react(m: LocalMessage, emoji: ReactionId) {
  await run(() => chat.react(props.conversationId, m, emoji))
}

const confirmDelete = ref<ChatMessage | null>(null)
async function doDelete() {
  const m = confirmDelete.value
  confirmDelete.value = null
  if (m) await run(() => chat.remove(props.conversationId, m.id))
}

// --- Kopfmenü -----------------------------------------------------------------------------

const headerMenu = ref(false)
const muteMenu = ref(false)
function closeHeader(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest?.('[data-header-menu]')) {
    headerMenu.value = false
    muteMenu.value = false
  }
  if (!(e.target as HTMLElement | null)?.closest?.('[data-message-menu]')) closeMenu()
}
const muteOptions: { key: 'h1' | 'h8' | 'd1' | 'forever'; ms: number | null }[] = [
  { key: 'h1', ms: 3_600_000 },
  { key: 'h8', ms: 8 * 3_600_000 },
  { key: 'd1', ms: 24 * 3_600_000 },
  { key: 'forever', ms: null },
]
async function setMute(ms: number | null | false) {
  headerMenu.value = false
  muteMenu.value = false
  const until = ms ? new Date(Date.now() + ms).toISOString() : null
  await run(() => chat.mute(props.conversationId, ms !== false, until))
}

async function markUnread(seq: number | null = null) {
  headerMenu.value = false
  closeMenu()
  await run(() => chat.markUnread(props.conversationId, seq))
}

const confirm = ref<'remove' | 'block' | 'leave' | null>(null)
async function doConfirm() {
  const kind = confirm.value
  const c = conversation.value
  confirm.value = null
  if (!kind || !c) return
  await run(async () => {
    if (kind === 'leave') {
      await chat.leaveGroup(c.id)
      toasts.ok(t('social.group.left', { name: title.value }))
      emit('removed')
      return
    }
    const peer = c.peer
    if (!peer) return
    if (kind === 'remove') {
      await backend.trs.removeFriend(peer.uuid)
      toasts.ok(t('friends.toasts.removed', { name: peer.name }))
    } else {
      await backend.trs.block(peer.uuid)
      toasts.ok(t('friends.toasts.blocked', { name: peer.name }))
    }
    await trs.loadFriends()
  })
}

// --- Melden, Links --------------------------------------------------------------------------

const reporting = ref<{ target: ReportTarget; label: string } | null>(null)
function report(target: ReportTarget, label: string) {
  closeMenu()
  headerMenu.value = false
  lightbox.value = null
  reporting.value = { target, label }
}

const link = ref<string | null>(null)

// --- Lebenszyklus -----------------------------------------------------------------------------

watch(
  () => props.conversationId,
  async (id) => {
    replyTo.value = null
    editing.value = null
    images.value = []
    pickerOpen.value = false
    unseen.value = 0
    await chat.open(id).catch((e) => toasts.error(e))
    await nextTick()
    toBottom()
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('mousedown', closeHeader)
  older = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) void loadOlder()
  }, { root: scroller.value, rootMargin: '200px 0px 0px 0px' })
  if (sentinel.value) older.observe(sentinel.value)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', closeHeader)
  older?.disconnect()
  unlistenDrop?.()
  if (clock) clearInterval(clock)
})
</script>

<template>
  <section v-if="conversation" class="relative flex min-h-0 min-w-0 flex-1 flex-col" :aria-label="title" data-testid="conversation">
    <!-- Kopf -->
    <header class="flex items-center gap-3 border-b border-base-800 bg-base-900/80 px-4 py-2.5">
      <SocialAvatar :conversation="conversation" :size="36" :online="peerPresence?.presence?.state ?? null" />
      <div class="min-w-0 flex-1">
        <h2 class="display flex items-center gap-2 truncate text-lg leading-tight text-base-50">
          {{ title }}
          <SocialIcon v-if="isMuted(conversation)" name="bellOff" class="size-4 shrink-0 text-base-400" />
        </h2>
        <p class="truncate text-xs text-base-400">
          <template v-if="conversation.kind === 'group'">{{ t('social.group.memberCount', conversation.members.length) }}</template>
          <template v-else-if="peerPresence">{{ trsPresenceText(peerPresence.presence) }}</template>
          <template v-else>{{ t('social.chat.notFriend') }}</template>
        </p>
      </div>
      <div class="relative" data-header-menu>
        <button class="btn-icon" :aria-label="t('social.menu.label')" :aria-expanded="headerMenu" data-testid="conversation-menu" @click="headerMenu = !headerMenu">
          <SocialIcon name="menu" class="size-4" />
        </button>
        <div v-if="headerMenu" class="menu top-11 right-0 w-60" role="menu">
          <template v-if="isMuted(conversation)">
            <button class="menu-item" role="menuitem" @click="setMute(false)"><SocialIcon name="bell" class="size-4" />{{ t('social.menu.unmute') }}</button>
          </template>
          <template v-else>
            <button class="menu-item" role="menuitem" @click="muteMenu = !muteMenu"><SocialIcon name="bellOff" class="size-4" />{{ t('social.menu.mute') }}</button>
            <div v-if="muteMenu" class="mb-1 ml-7 border-l border-base-700 pl-1">
              <button v-for="o in muteOptions" :key="o.key" class="menu-item py-1 text-xs" role="menuitem" @click="setMute(o.ms)">
                {{ t(`social.menu.muteFor.${o.key}`) }}
              </button>
            </div>
          </template>
          <button class="menu-item" role="menuitem" @click="markUnread()"><SocialIcon name="mailUnread" class="size-4" />{{ t('social.message.markUnread') }}</button>
          <div class="my-1 border-t border-base-700" />
          <template v-if="conversation.kind === 'dm' && conversation.peer">
            <button v-if="peerPresence" class="menu-item" role="menuitem" @click="headerMenu = false; confirm = 'remove'"><SocialIcon name="close" class="size-4" />{{ t('social.menu.removeFriend') }}</button>
            <button class="menu-item" role="menuitem" @click="headerMenu = false; confirm = 'block'"><SocialIcon name="block" class="size-4" />{{ t('social.menu.block') }}</button>
            <button
              class="menu-item text-redstone-300"
              role="menuitem"
              @click="report({ kind: 'player', uuid: conversation.peer.uuid, conversationId: conversation.id }, conversation.peer.name)"
            >
              <SocialIcon name="flag" class="size-4" />{{ t('social.menu.reportPlayer') }}
            </button>
          </template>
          <template v-else>
            <button class="menu-item" role="menuitem" @click="headerMenu = false; emit('manageGroup')">
              <SocialIcon name="gear" class="size-4" />{{ isOwner ? t('social.menu.manageGroup') : t('social.menu.groupMembers') }}
            </button>
            <button class="menu-item" role="menuitem" @click="headerMenu = false; confirm = 'leave'"><SocialIcon name="leave" class="size-4" />{{ t('social.menu.leaveGroup') }}</button>
            <button class="menu-item text-redstone-300" role="menuitem" @click="report({ kind: 'group', conversationId: conversation.id }, title)">
              <SocialIcon name="flag" class="size-4" />{{ t('social.menu.reportGroup') }}
            </button>
          </template>
        </div>
      </div>
    </header>

    <!-- Verlauf -->
    <div ref="scroller" class="relative min-h-0 flex-1 overflow-y-auto py-3" data-testid="messages" @scroll.passive="onScroll">
      <div ref="sentinel" class="h-px" />
      <div v-if="thread?.loading && thread.hasMore" class="py-2 text-center text-xs text-base-400">{{ t('common.status.loading') }}</div>
      <p v-else-if="thread?.loaded && !thread.hasMore" class="mx-auto mb-4 max-w-sm px-4 text-center text-xs text-base-400">
        {{ conversation.kind === 'dm' ? t('social.chat.startDm', { name: title }) : t('social.chat.startGroup', { name: title }) }}
      </p>
      <div v-if="!thread?.loaded" class="space-y-3 px-4">
        <div v-for="i in 4" :key="i" class="skeleton h-10" :class="i % 2 ? 'mr-auto w-1/2' : 'ml-auto w-1/3'" />
      </div>
      <template v-for="item in items" :key="item.key">
        <div v-if="item.type === 'day'" class="day-sep my-3 flex items-center gap-3 px-4 text-[11px] text-base-400">
          <span class="h-px flex-1 bg-base-800" />{{ item.label }}<span class="h-px flex-1 bg-base-800" />
        </div>
        <SocialMessage
          v-else
          :message="item.message"
          :conversation="conversation"
          :mine="item.mine"
          :first="item.first"
          :last="item.last"
          :receipt="receiptFor(item.message)"
          :highlighted="highlight === item.message.id"
          @reply="replyTo = item.message; editing = null; composer?.focus()"
          @react="(e) => react(item.message, e)"
          @menu="(e) => openMenu(item.message, e)"
          @image="(i) => openImage(item.message, i)"
          @link="(href) => (link = href)"
          @jump="jump"
          @retry="chat.retry(conversationId, item.message)"
          @discard="chat.discard(conversationId, item.message)"
        />
      </template>
    </div>

    <!-- Hinweis auf neue Nachrichten, wenn man weiter oben liest -->
    <button
      v-if="unseen > 0 && !atBottom"
      class="btn btn-primary absolute right-6 bottom-28 z-10 px-3 py-1.5 text-xs shadow-lg"
      @click="toBottom(true)"
    >
      {{ t('social.chat.newMessages', unseen) }} ↓
    </button>

    <!-- schreibt … -->
    <p class="h-5 px-5 text-xs text-base-400" aria-live="polite">
      <span v-if="typers.length" class="inline-flex items-center gap-2" data-testid="typing"><span class="dots" aria-hidden="true"><i /><i /><i /></span>{{ typingText(typers) }}</span>
    </p>

    <!-- Bildauswahl über der Eingabe -->
    <div v-if="pickerOpen" class="absolute inset-x-0 bottom-[5.5rem] z-20 flex h-[62%] flex-col border-t border-base-700 shadow-2xl">
      <SocialImagePicker v-model="images" @done="pickerOpen = false; composer?.focus()" />
    </div>

    <div v-if="dragging" class="pointer-events-none absolute inset-2 z-30 grid place-items-center rounded-xl border-2 border-dashed border-redstone-500 bg-base-950/70 text-sm text-base-50">
      {{ t('social.picker.dropHere') }}
    </div>

    <SocialComposer
      ref="composer"
      v-model:reply-to="replyTo"
      v-model:editing="editing"
      v-model:images="images"
      :conversation="conversation"
      :picker-open="pickerOpen"
      @toggle-picker="pickerOpen = !pickerOpen"
      @invite="inviting = true"
      @edit-last="editLast"
      @sent="pickerOpen = false"
    />

    <!-- Kontextmenü einer Nachricht -->
    <div
      v-if="menu"
      class="menu fixed z-50 w-52"
      :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
      role="menu"
      data-message-menu
      data-testid="message-menu"
    >
      <template v-if="!menu.message.deleted && !menu.message.hidden">
        <button v-if="conversation.canWrite" class="menu-item" role="menuitem" @click="replyTo = menu.message; editing = null; closeMenu(); composer?.focus()">
          <SocialIcon name="reply" class="size-4" />{{ t('social.message.reply') }}
        </button>
        <button v-if="menu.message.text || menu.message.invite" class="menu-item" role="menuitem" @click="copy(menu.message); closeMenu()">
          <SocialIcon name="copy" class="size-4" />{{ t('common.actions.copy') }}
        </button>
        <button v-if="menuMine && menu.message.text !== null && conversation.canWrite" class="menu-item" role="menuitem" @click="editing = menu.message; replyTo = null; closeMenu()">
          <SocialIcon name="edit" class="size-4" />{{ t('common.actions.edit') }}
        </button>
      </template>
      <button v-if="!menuMine" class="menu-item" role="menuitem" @click="markUnread(menu.message.seq)">
        <SocialIcon name="mailUnread" class="size-4" />{{ t('social.message.markUnread') }}
      </button>
      <div class="my-1 border-t border-base-700" />
      <button v-if="menuCanDelete && !menu.message.deleted" class="menu-item text-redstone-300" role="menuitem" @click="confirmDelete = menu.message; closeMenu()">
        <SocialIcon name="trash" class="size-4" />{{ t('common.actions.delete') }}
      </button>
      <button
        v-if="!menuMine && !menu.message.deleted && !menu.message.hidden"
        class="menu-item text-redstone-300"
        role="menuitem"
        @click="report({ kind: 'message', messageId: menu.message.id }, menu.message.sender?.name ?? '')"
      >
        <SocialIcon name="flag" class="size-4" />{{ t('social.message.report') }}
      </button>
    </div>

    <!-- Dialoge -->
    <SocialLightbox
      v-if="lightbox"
      :urls="lightbox.message.attachments.map((a) => attachmentUrl(a.id))"
      :start="lightbox.index"
      :can-report="lightbox.message.sender?.uuid !== chat.me"
      @close="lightbox = null"
      @report="(i) => report({ kind: 'image', attachmentId: lightbox!.message.attachments[i]!.id }, lightbox!.message.sender?.name ?? '')"
    />
    <SocialReportDialog v-if="reporting" :target="reporting.target" :label="reporting.label" @close="reporting = null" />
    <SocialInviteDialog v-if="inviting" :conversation-id="conversationId" @close="inviting = false" />
    <SocialLinkDialog v-if="link" :href="link" @close="link = null" />

    <BaseDialog v-if="confirmDelete" :title="t('social.message.deleteTitle')" @close="confirmDelete = null">
      <p class="text-sm text-base-200">{{ confirmDelete.sender?.uuid === chat.me ? t('social.message.deleteText') : t('social.message.deleteOthersText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" data-testid="confirm-delete" @click="doDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog
      v-if="confirm"
      :title="confirm === 'remove' ? t('friends.confirm.removeTitle') : confirm === 'block' ? t('friends.confirm.blockTitle') : t('social.group.leaveTitle')"
      @close="confirm = null"
    >
      <p class="text-sm text-base-200">
        {{ confirm === 'leave' ? t('social.group.leaveText', { name: title }) : confirm === 'remove' ? t('social.confirm.remove', { name: title }) : t('social.confirm.block', { name: title }) }}
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirm = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="doConfirm">
          {{ confirm === 'leave' ? t('social.menu.leaveGroup') : confirm === 'remove' ? t('common.actions.remove') : t('friends.list.block') }}
        </button>
      </template>
    </BaseDialog>
  </section>
</template>

<style scoped>
.dots {
  display: inline-flex;
  gap: 3px;
}
.dots i {
  width: 5px;
  height: 5px;
  background: var(--color-redstone-400);
  animation: blink 1.2s infinite;
}
.dots i:nth-child(2) {
  animation-delay: 0.2s;
}
.dots i:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
  }
  40% {
    opacity: 1;
  }
}
</style>
