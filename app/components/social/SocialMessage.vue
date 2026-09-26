<script setup lang="ts">
import {
  REACTIONS,
  attachmentUrl,
  messageTime,
  onlyEmoji,
  splitLinks,
  systemText,
  type ChatConversation,
  type LocalMessage,
  type ReactionId,
} from '~/utils/chat'

// Eine Nachricht im Verlauf: Blase (eigene rechts in Akzentfarbe), Antwort-
// Zitat, Text mit sicheren Links, Bilder, Server-Einladung, Reaktionen,
// „bearbeitet“, gelöscht/ausgeblendet und der Sendestatus eigener Nachrichten.
// Aktionen (Antworten, Reagieren, Menü) erscheinen beim Drüberfahren.
const props = defineProps<{
  message: LocalMessage
  conversation: ChatConversation
  mine: boolean
  first: boolean
  last: boolean
  /** Lesestatus (nur unter meiner letzten Nachricht). */
  receipt?: { read: boolean; count: number } | null
  highlighted?: boolean
}>()
const emit = defineEmits<{
  reply: []
  react: [emoji: ReactionId]
  menu: [event: MouseEvent]
  image: [index: number]
  link: [href: string]
  jump: [messageId: string]
  retry: []
  discard: []
}>()

const chat = useChatStore()
const group = computed(() => props.conversation.kind === 'group')
const m = computed(() => props.message)
const parts = computed(() => (m.value.text ? splitLinks(m.value.text) : []))
const big = computed(() => onlyEmoji(m.value.text) && !m.value.attachments.length && !m.value.replyTo && !m.value.invite)
const pickerOpen = ref(false)
const canAct = computed(() => !m.value.local && !m.value.deleted && !m.value.hidden && m.value.kind === 'text')

/** Namen derer, die reagiert haben (Tooltip). */
function reactors(users: string[]): string {
  const names = users.map(
    (u) => (u === chat.me ? t('social.chat.youShort') : props.conversation.members.find((mm) => mm.uuid === u)?.name ?? props.conversation.peer?.name) ?? '?',
  )
  return names.join(', ')
}

function deletedText(): string {
  if (m.value.deletedBy === 'admin') return t('social.chat.deletedByAdmin')
  if (m.value.deletedBy === 'owner') return t('social.chat.deletedByOwner')
  return t('social.chat.deleted')
}

function replyText(): string {
  const r = m.value.replyTo
  if (!r) return ''
  if (r.deleted || !r.preview) {
    if (r.deleted) return t('social.chat.deleted')
    if (r.attachments) return t('social.chat.images', r.attachments)
    if (r.invite) return t('social.chat.invite')
  }
  return r.preview ?? ''
}

/** Name in Gruppen farbig (stabil je Spieler). */
function nameColor(uuid: string | undefined): string {
  const palette = ['text-redstone-300', 'text-lamp-300', 'text-ok', 'text-sky-300', 'text-violet-300', 'text-pink-300']
  if (!uuid) return 'text-base-200'
  let h = 0
  for (const ch of uuid) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return palette[h % palette.length]!
}

const images = computed(() => (m.value.local ? m.value.local.previews : m.value.attachments.map((a) => attachmentUrl(a.id, true))))
const gridClass = computed(() => {
  const n = images.value.length
  if (n <= 1) return 'grid-cols-1'
  if (n === 2 || n === 4) return 'grid-cols-2'
  return 'grid-cols-3'
})
</script>

<template>
  <!-- Systemnachricht: mittig, dezent -->
  <div v-if="m.kind === 'system'" class="my-2 flex justify-center px-6" :data-message-id="m.id">
    <span class="rounded-full bg-base-850 px-3 py-1 text-center text-xs text-base-400">{{ systemText(m.system) }}</span>
  </div>

  <div
    v-else
    class="group/msg relative flex gap-2 px-4"
    :class="[mine ? 'flex-row-reverse' : '', first ? 'mt-3' : 'mt-0.5', highlighted ? 'msg-highlight' : '']"
    :data-message-id="m.id"
    @contextmenu.prevent="emit('menu', $event)"
  >
    <!-- Gesicht in Gruppen (nur am Anfang einer Gruppe) -->
    <div v-if="group && !mine" class="w-7 shrink-0">
      <span v-if="first" class="block size-7 overflow-hidden rounded-md">
        <PlayerFace v-if="m.sender" :uuid="m.sender.uuid" :name="m.sender.name" />
      </span>
    </div>

    <div class="flex max-w-[72%] min-w-0 flex-col" :class="mine ? 'items-end' : 'items-start'">
      <p v-if="first" class="mb-1 flex items-baseline gap-2 px-1 text-[11px] text-base-400" :class="mine ? 'flex-row-reverse' : ''">
        <span v-if="group && !mine && m.sender" class="font-semibold" :class="nameColor(m.sender.uuid)">{{ m.sender.name }}</span>
        <span>{{ messageTime(m.createdAt) }}</span>
      </p>

      <div class="relative flex items-center gap-1" :class="mine ? 'flex-row-reverse' : ''">
        <!-- Blase -->
        <div
          class="bubble min-w-0"
          :class="[
            big ? 'bubble-emoji' : mine ? 'bubble-mine' : 'bubble-other',
            m.deleted || m.hidden ? 'bubble-muted' : '',
            m.local?.state === 'sending' ? 'opacity-70' : '',
            m.local?.state === 'failed' ? 'ring-1 ring-redstone-400' : '',
          ]"
        >
          <p v-if="m.deleted" class="text-sm italic opacity-80">{{ deletedText() }}</p>
          <p v-else-if="m.hidden" class="text-sm italic opacity-80">{{ t('social.chat.hidden') }}</p>
          <template v-else>
            <button
              v-if="m.replyTo"
              class="mb-1.5 block w-full rounded-md border-l-2 px-2 py-1 text-left text-xs"
              :class="mine ? 'border-white/60 bg-black/15' : 'border-redstone-400 bg-base-900/60'"
              :title="t('social.chat.jumpToReply')"
              @click="emit('jump', m.replyTo.id)"
            >
              <span class="block font-semibold opacity-90">{{ m.replyTo.sender?.name ?? '?' }}</span>
              <span class="block truncate opacity-80" :class="{ italic: m.replyTo.deleted }">{{ replyText() }}</span>
            </button>

            <div v-if="images.length" class="grid gap-1" :class="[gridClass, m.text || m.invite ? 'mb-1.5' : '']">
              <button
                v-for="(src, i) in images"
                :key="i"
                class="chat-image overflow-hidden rounded-md bg-black/20"
                :class="images.length === 1 ? 'single' : 'tile'"
                :aria-label="t('social.chat.openImage')"
                :disabled="!!m.local"
                @click="emit('image', i)"
              >
                <img :src="src" alt="" loading="lazy" draggable="false" />
              </button>
            </div>

            <SocialInviteCard v-if="m.invite" :invite="m.invite" :class="m.text ? 'mb-1.5' : ''" />

            <p v-if="parts.length" class="msg-text" :class="big ? 'text-4xl leading-tight' : 'text-sm'">
              <template v-for="(part, i) in parts" :key="i">
                <button v-if="part.type === 'link'" class="msg-link" :title="part.href" @click="emit('link', part.href)">{{ part.value }}</button>
                <template v-else>{{ part.value }}</template>
              </template>
            </p>
          </template>
          <span v-if="m.editedAt && !m.deleted" class="mt-0.5 block text-right text-[10px] opacity-70">{{ t('social.chat.edited') }}</span>
        </div>

        <!-- Aktionen beim Drüberfahren -->
        <div v-if="canAct" class="msg-tools flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover/msg:opacity-100 focus-within:opacity-100">
          <div class="relative">
            <button class="tool" :aria-label="t('social.message.react')" :title="t('social.message.react')" @click="pickerOpen = !pickerOpen">
              <SocialIcon name="smile" class="size-4" />
            </button>
            <SocialReactionPicker v-if="pickerOpen" :class="mine ? 'right-0' : 'left-0'" @pick="(e) => { pickerOpen = false; emit('react', e) }" @close="pickerOpen = false" />
          </div>
          <button v-if="conversation.canWrite" class="tool" :aria-label="t('social.message.reply')" :title="t('social.message.reply')" @click="emit('reply')">
            <SocialIcon name="reply" class="size-4" />
          </button>
          <button class="tool" :aria-label="t('social.message.more')" :title="t('social.message.more')" @click="emit('menu', $event)">
            <SocialIcon name="dots" class="size-4" :stroke="3" />
          </button>
        </div>
      </div>

      <!-- Reaktionen -->
      <div v-if="m.reactions.length" class="mt-1 flex flex-wrap gap-1" :class="mine ? 'justify-end' : ''">
        <button
          v-for="r in m.reactions"
          :key="r.emoji"
          class="reaction"
          :class="{ 'reaction-on': chat.me && r.users.includes(chat.me) }"
          :title="reactors(r.users)"
          :aria-pressed="!!chat.me && r.users.includes(chat.me)"
          :disabled="!canAct"
          @click="emit('react', r.emoji)"
        >
          <span>{{ REACTIONS[r.emoji] }}</span>
          <span class="tabular-nums">{{ r.count }}</span>
        </button>
      </div>

      <!-- Sendestatus / Lesestatus -->
      <p v-if="m.local?.state === 'sending'" class="mt-0.5 px-1 text-[10px] text-base-400">{{ t('social.chat.sending') }}</p>
      <p v-else-if="m.local?.state === 'failed'" class="mt-0.5 flex flex-wrap items-center gap-2 px-1 text-[11px] text-redstone-300" role="alert">
        <span>{{ m.local.error || t('social.chat.failed') }}</span>
        <button class="underline hover:text-redstone-200" @click="emit('retry')">{{ t('common.actions.retry') }}</button>
        <button class="underline hover:text-redstone-200" @click="emit('discard')">{{ t('social.chat.discard') }}</button>
      </p>
      <p v-else-if="receipt" class="mt-0.5 flex items-center gap-1 px-1 text-[10px] text-base-400" data-testid="read-receipt">
        <SocialIcon name="check" class="size-3" :class="receipt.read ? 'text-redstone-300' : ''" />
        {{ receipt.read ? (group ? t('social.chat.readBy', receipt.count) : t('social.chat.read')) : t('social.chat.sent') }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.bubble {
  border-radius: 0.875rem;
  padding: 0.45rem 0.75rem;
  overflow-wrap: anywhere;
}
.bubble-mine {
  background: var(--color-redstone-500);
  color: #fff;
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.18),
    inset 0 -2px 0 rgb(0 0 0 / 0.18);
  border-bottom-right-radius: 0.3rem;
}
.bubble-other {
  background: var(--color-base-800);
  color: var(--color-base-50);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.03);
  border-bottom-left-radius: 0.3rem;
}
.bubble-emoji {
  background: transparent;
  padding: 0;
}
.bubble-muted {
  background: transparent;
  color: var(--color-base-400);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
.msg-text {
  white-space: pre-wrap;
  user-select: text;
}
.msg-link {
  display: inline;
  text-decoration: underline;
  text-underline-offset: 2px;
  word-break: break-all;
  text-align: left;
}
.bubble-other .msg-link {
  color: var(--color-redstone-300);
}
.chat-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.chat-image.single {
  max-width: 22rem;
  max-height: 16rem;
}
.chat-image.single img {
  object-fit: contain;
  max-height: 16rem;
}
.chat-image.tile {
  width: 7.5rem;
  height: 7.5rem;
}
.tool {
  display: inline-grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 0.375rem;
  color: var(--color-base-400);
}
.tool:hover {
  background: var(--color-base-800);
  color: var(--color-base-50);
}
.reaction {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-850);
  padding: 0 0.45rem;
  font-size: 0.75rem;
  line-height: 1.35rem;
  color: var(--color-base-200);
}
.reaction:hover:not(:disabled) {
  border-color: var(--color-base-600);
}
.reaction-on {
  border-color: var(--color-redstone-500);
  background: color-mix(in srgb, var(--color-redstone-900) 70%, transparent);
  color: var(--color-base-50);
}
.msg-highlight .bubble {
  box-shadow: 0 0 0 2px var(--color-lamp-400);
}
</style>
