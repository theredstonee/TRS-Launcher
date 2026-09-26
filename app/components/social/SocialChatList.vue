<script setup lang="ts">
import { conversationPreview, conversationTitle, isMuted, listTime, matchesSearch, unreadCount } from '~/utils/chat'

// Liste der Unterhaltungen (neueste zuerst): Gesicht/Gruppe, Name, letzte
// Nachricht oder „Klicke, um zu schreiben“, Datum, Ungelesen-Zähler und
// Stumm-Symbol. Freunde ohne Unterhaltung stehen darunter – ein Klick öffnet
// die Direktnachricht.
const props = defineProps<{ search: string }>()
const emit = defineEmits<{ open: [id: string]; openFriend: [uuid: string] }>()

const chat = useChatStore()
const trs = useTrsStore()

const conversations = computed(() => chat.list.filter((c) => matchesSearch(c, props.search, chat.me)))
/** Freunde, mit denen es noch keine Unterhaltung gibt. */
const friendsWithout = computed(() => {
  const withDm = new Set(chat.list.filter((c) => c.kind === 'dm').map((c) => c.peer?.uuid))
  const q = props.search.trim().toLocaleLowerCase()
  return trsSortFriends(trs.friends?.friends ?? []).filter(
    (f) => !withDm.has(f.uuid) && (!q || f.name.toLocaleLowerCase().includes(q)),
  )
})
const presenceOf = (uuid: string | undefined) => trs.friends?.friends.find((f) => f.uuid === uuid)?.presence?.state ?? null
</script>

<template>
  <div class="flex min-h-0 flex-col">
    <div v-if="chat.listState === 'loading' && !chat.list.length" class="space-y-2 p-2">
      <div v-for="i in 5" :key="i" class="skeleton h-14" />
    </div>
    <ul v-else class="min-h-0 flex-1 space-y-0.5 overflow-y-auto p-1.5" role="listbox" :aria-label="t('social.chat.listLabel')" data-testid="chat-list">
      <li v-for="c in conversations" :key="c.id">
        <button
          class="group flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left transition-colors"
          :class="chat.activeId === c.id ? 'bg-base-800' : 'hover:bg-base-850'"
          role="option"
          :aria-selected="chat.activeId === c.id"
          @click="emit('open', c.id)"
        >
          <SocialAvatar :conversation="c" :size="40" :online="c.kind === 'dm' ? presenceOf(c.peer?.uuid) : null" />
          <span class="min-w-0 flex-1">
            <span class="flex items-baseline gap-2">
              <span class="truncate text-sm font-semibold text-base-50">{{ conversationTitle(c, chat.me) }}</span>
              <SocialIcon v-if="isMuted(c)" name="bellOff" class="size-3.5 shrink-0 text-base-400" :title="t('social.chat.mutedLabel')" />
              <span class="ml-auto shrink-0 text-[11px] text-base-400">{{ listTime(c.lastMessage?.createdAt ?? null) }}</span>
            </span>
            <span class="flex items-center gap-2">
              <span
                class="truncate text-xs"
                :class="unreadCount(c) ? 'font-medium text-base-100' : c.lastMessage ? 'text-base-400' : 'text-base-400 italic'"
              >
                <SocialIcon v-if="c.lastMessage?.invite" name="invite" class="mr-1 inline size-3 align-[-2px]" />
                {{ conversationPreview(c, chat.me) }}
              </span>
              <span
                v-if="unreadCount(c)"
                class="ml-auto shrink-0 rounded-full bg-redstone-500 px-1.5 text-[10px] leading-4 font-bold text-white"
                :aria-label="t('social.chat.unreadLabel', unreadCount(c))"
              >
                {{ unreadCount(c) > 99 ? '99+' : unreadCount(c) }}
              </span>
              <span v-else-if="c.markedUnread && isMuted(c)" class="ml-auto size-2 shrink-0 rounded-full bg-base-400" />
            </span>
          </span>
        </button>
      </li>
      <li v-if="friendsWithout.length" class="px-2.5 pt-3 pb-1 text-[11px] font-medium tracking-wide text-base-400 uppercase">
        {{ t('social.chat.startWith') }}
      </li>
      <li v-for="f in friendsWithout" :key="f.uuid">
        <button class="flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-left hover:bg-base-850" @click="emit('openFriend', f.uuid)">
          <SocialAvatar :uuid="f.uuid" :name="f.name" :size="40" :online="f.presence?.state ?? null" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-semibold text-base-50">{{ f.name }}</span>
            <span class="block truncate text-xs text-base-400 italic">{{ t('social.chat.clickToWrite') }}</span>
          </span>
        </button>
      </li>
      <li v-if="!conversations.length && !friendsWithout.length && chat.listState !== 'loading'" class="px-3 py-8 text-center text-sm text-base-400">
        {{ search.trim() ? t('social.chat.noMatches') : t('social.chat.empty') }}
      </li>
      <li v-if="chat.nextCursor && !search.trim()" class="p-2">
        <button class="btn btn-ghost w-full py-1.5 text-xs" @click="chat.loadList(true).catch(() => {})">{{ t('social.chat.loadMore') }}</button>
      </li>
    </ul>
  </div>
</template>
