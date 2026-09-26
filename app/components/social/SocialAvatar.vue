<script setup lang="ts">
import type { ChatConversation } from '~/utils/chat'

// Bild einer Unterhaltung: Gesicht des Freundes, bei Gruppen ein Stapel aus
// bis zu vier Gesichtern (Redstone-Rahmen). Optional mit Online-Punkt.
const props = withDefaults(
  defineProps<{ conversation?: ChatConversation | null; uuid?: string; name?: string; size?: number; online?: 'online' | 'in-game' | null }>(),
  { conversation: null, uuid: '', name: '', size: 40, online: null },
)
const chat = useChatStore()

const faces = computed(() => {
  const c = props.conversation
  if (!c) return [{ uuid: props.uuid, name: props.name }]
  if (c.kind === 'dm' && c.peer) return [c.peer]
  return c.members.filter((m) => m.uuid !== chat.me).slice(0, 4)
})
const group = computed(() => props.conversation?.kind === 'group')
</script>

<template>
  <span class="relative inline-block shrink-0" :style="{ width: `${size}px`, height: `${size}px` }">
    <span
      v-if="!group"
      class="block size-full overflow-hidden rounded-md ring-1 ring-black/30"
    >
      <PlayerFace v-if="faces[0]" :uuid="faces[0].uuid" :name="faces[0].name" />
    </span>
    <span v-else class="grid size-full grid-cols-2 grid-rows-2 gap-px overflow-hidden rounded-md bg-redstone-900/60 p-px ring-1 ring-redstone-600/50">
      <span v-for="f in faces" :key="f.uuid" class="block overflow-hidden" :class="{ 'col-span-2': faces.length === 1, 'row-span-2': faces.length <= 2 }">
        <PlayerFace :uuid="f.uuid" :name="f.name" />
      </span>
      <span v-if="!faces.length" class="col-span-2 row-span-2 grid place-items-center text-redstone-300">
        <SocialIcon name="friends" class="size-1/2" />
      </span>
    </span>
    <span
      v-if="online"
      class="absolute -right-0.5 -bottom-0.5 size-3 rounded-full ring-2 ring-base-900"
      :class="online === 'in-game' ? 'bg-lamp-400' : 'bg-ok'"
    />
  </span>
</template>
