<script setup lang="ts">
import { MAX_IMAGES, MAX_TEXT, codePoints, conversationTitle, messageSummary, type ChatConversation, type ChatMessage } from '~/utils/chat'
import type { DraftImage } from '~/stores/chat'

// Eingabe unten im Chat: Enter sendet, Umschalt+Enter macht eine neue Zeile,
// Esc bricht Antworten/Bearbeiten ab, Pfeil hoch bearbeitet die letzte eigene
// Nachricht. Bilder (Auswahl, Einfügen, Drag & Drop) und Server-Einladungen.
const props = defineProps<{
  conversation: ChatConversation
  replyTo: ChatMessage | null
  editing: ChatMessage | null
  images: DraftImage[]
  pickerOpen: boolean
}>()
const emit = defineEmits<{
  'update:replyTo': [value: ChatMessage | null]
  'update:editing': [value: ChatMessage | null]
  'update:images': [value: DraftImage[]]
  togglePicker: []
  invite: []
  editLast: []
  sent: []
}>()

const chat = useChatStore()
const toasts = useToasts()
const sanctions = useSanctionsStore()
const input = useTemplateRef<HTMLTextAreaElement>('input')
const text = ref(chat.drafts[props.conversation.id] ?? '')
const sending = ref(false)

const blocked = computed(() => {
  if (chat.chatMuted || sanctions.chatMuted) return 'muted'
  if (!props.conversation.canWrite) return props.conversation.readOnlyReason ?? 'readOnly'
  return null
})
const length = computed(() => codePoints(text.value))
const tooLong = computed(() => length.value > MAX_TEXT)
const canSend = computed(
  () => !blocked.value && !sending.value && !tooLong.value && (text.value.trim().length > 0 || (!props.editing && props.images.length > 0)),
)
const placeholder = computed(() =>
  props.conversation.kind === 'dm'
    ? t('social.composer.placeholder', { name: conversationTitle(props.conversation, chat.me) })
    : t('social.composer.placeholderGroup', { name: conversationTitle(props.conversation, chat.me) }),
)
const muteSanction = computed(() => sanctions.sanctionOf('chat_mute'))
const muteUntil = computed(() => muteSanction.value?.endsAt ?? chat.moderation?.mute?.until ?? null)

watch(
  () => props.conversation.id,
  (id, before) => {
    if (before) chat.drafts[before] = text.value
    text.value = chat.drafts[id] ?? ''
    void nextTick(resize)
  },
)
watch(
  () => props.editing,
  (m) => {
    if (m) {
      text.value = m.text ?? ''
      void nextTick(() => {
        resize()
        input.value?.focus()
      })
    }
  },
)
watch(
  () => props.replyTo,
  (m) => {
    if (m) void nextTick(() => input.value?.focus())
  },
)

function resize() {
  const el = input.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 180)}px`
}

function onInput() {
  resize()
  chat.drafts[props.conversation.id] = text.value
  if (props.editing) return
  if (text.value.trim()) chat.noteTyping(props.conversation.id)
  else void chat.stopTyping(props.conversation.id)
}

async function submit() {
  if (!canSend.value) return
  const id = props.conversation.id
  if (props.editing) {
    const message = props.editing
    if ((message.text ?? '') === text.value.trim()) {
      cancel()
      return
    }
    sending.value = true
    try {
      await chat.edit(id, message.id, text.value)
      cancel()
    } catch (e) {
      toasts.error(e)
    } finally {
      sending.value = false
    }
    return
  }
  const draft = { text: text.value, replyTo: props.replyTo, images: props.images }
  text.value = ''
  chat.drafts[id] = ''
  emit('update:replyTo', null)
  emit('update:images', [])
  void nextTick(resize)
  emit('sent')
  await chat.send(id, draft)
}

function cancel() {
  if (props.editing) {
    emit('update:editing', null)
    text.value = ''
  }
  emit('update:replyTo', null)
  void nextTick(resize)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    void submit()
  } else if (e.key === 'Escape' && (props.editing || props.replyTo)) {
    e.preventDefault()
    cancel()
  } else if (e.key === 'ArrowUp' && !text.value && !props.editing) {
    emit('editLast')
  }
}

function removeImage(index: number) {
  emit('update:images', props.images.filter((_, i) => i !== index))
}

/** Bild aus der Zwischenablage einfügen – der Kern prüft es. */
async function onPaste(e: ClipboardEvent) {
  const files = [...(e.clipboardData?.files ?? [])].filter((f) => f.type.startsWith('image/'))
  if (!files.length || props.editing) return
  e.preventDefault()
  for (const file of files.slice(0, MAX_IMAGES - props.images.length)) {
    if (file.size > 16 * 1024 * 1024) {
      toasts.error(t('social.picker.tooBig'))
      continue
    }
    try {
      const data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ''))
        reader.onerror = () => reject(reader.error)
        reader.readAsDataURL(file)
      })
      const image = await backend.social.stagePasted(file.name || 'image.png', data)
      emit('update:images', [...props.images, { source: { kind: 'local', id: image.id }, preview: localImageUrl(image.id) }])
    } catch (err) {
      toasts.error(err)
    }
  }
}

function focus() {
  input.value?.focus()
}
defineExpose({ focus })
onMounted(() => nextTick(resize))
onBeforeUnmount(() => {
  chat.drafts[props.conversation.id] = text.value
  void chat.stopTyping(props.conversation.id)
})
</script>

<template>
  <div class="border-t border-base-800 bg-base-900/80 px-3 pt-2 pb-3">
    <!-- Gesperrt: nicht befreundet / stummgeschaltet -->
    <p v-if="blocked" class="flex items-center gap-2 rounded-lg bg-base-850 px-3 py-2.5 text-sm text-base-400" role="status" data-testid="composer-blocked">
      <SocialIcon :name="blocked === 'muted' ? 'bellOff' : 'block'" class="size-4 shrink-0" />
      <span v-if="blocked === 'muted'">
        {{ muteUntil ? t('social.moderation.mutedUntil', { date: dateTime(muteUntil) }) : t('social.moderation.mutedReview') }}
      </span>
      <span v-else-if="blocked === 'not_friends'">{{ t('social.composer.notFriends') }}</span>
      <span v-else>{{ t('social.composer.readOnly') }}</span>
      <button v-if="blocked === 'muted'" class="ml-auto shrink-0 text-xs text-redstone-300 hover:underline" data-testid="composer-sanction" @click="sanctions.open(muteSanction?.id ?? null)">
        {{ muteSanction?.appealable ? t('sanctions.appeal.button') : t('sanctions.banner.details') }}
      </button>
    </p>

    <template v-else>
      <!-- Antworten / Bearbeiten -->
      <div v-if="replyTo || editing" class="mb-2 flex items-center gap-2 rounded-lg border-l-2 border-redstone-500 bg-base-850 px-3 py-1.5 text-xs">
        <SocialIcon :name="editing ? 'edit' : 'reply'" class="size-3.5 shrink-0 text-redstone-300" />
        <span class="min-w-0 flex-1 truncate">
          <template v-if="editing">{{ t('social.composer.editing') }}</template>
          <template v-else-if="replyTo">
            <span class="font-semibold text-base-100">{{ t('social.composer.replyingTo', { name: replyTo.sender?.name ?? '?' }) }}</span>
            <span class="text-base-400"> · {{ messageSummary(replyTo) }}</span>
          </template>
        </span>
        <button class="btn-icon size-6 bg-transparent" :aria-label="t('common.actions.cancel')" @click="cancel">
          <SocialIcon name="close" class="size-3.5" />
        </button>
      </div>

      <!-- Ausgewählte Bilder -->
      <ul v-if="images.length && !editing" class="mb-2 flex gap-2 overflow-x-auto pb-1" data-testid="composer-images">
        <li v-for="(img, i) in images" :key="i" class="relative size-16 shrink-0 overflow-hidden rounded-md bg-base-850 ring-1 ring-base-700">
          <img v-if="img.preview" :src="img.preview" alt="" class="size-full object-cover" />
          <button class="absolute top-0.5 right-0.5 grid size-5 place-items-center rounded bg-black/70 text-white" :aria-label="t('social.composer.removeImage')" @click="removeImage(i)">
            <SocialIcon name="close" class="size-3" :stroke="3" />
          </button>
        </li>
      </ul>

      <div class="composer flex items-end gap-2 rounded-xl px-2 py-1.5">
        <button
          v-if="!editing"
          class="btn-icon size-9"
          :class="{ 'bg-redstone-500 text-white hover:bg-redstone-400': pickerOpen }"
          :aria-label="t('social.composer.images')"
          :title="t('social.composer.images')"
          :aria-pressed="pickerOpen"
          data-testid="composer-picker"
          @click="emit('togglePicker')"
        >
          <SocialIcon name="image" class="size-4.5" />
        </button>
        <button
          v-if="!editing"
          class="btn-icon size-9"
          :aria-label="t('social.composer.invite')"
          :title="t('social.composer.invite')"
          data-testid="composer-invite"
          @click="emit('invite')"
        >
          <SocialIcon name="invite" class="size-4.5" />
        </button>
        <textarea
          ref="input"
          v-model="text"
          rows="1"
          class="min-h-9 flex-1 resize-none bg-transparent px-1 py-2 text-sm text-base-50 outline-none placeholder:text-base-400"
          :placeholder="placeholder"
          :aria-label="placeholder"
          spellcheck="true"
          data-testid="composer-input"
          @input="onInput"
          @keydown="onKey"
          @paste="onPaste"
        />
        <span v-if="length > MAX_TEXT - 200" class="self-center text-[11px] tabular-nums" :class="tooLong ? 'text-redstone-300' : 'text-base-400'">
          {{ length }}/{{ MAX_TEXT }}
        </span>
        <button
          class="btn btn-primary size-9 shrink-0 p-0"
          :disabled="!canSend"
          :aria-label="editing ? t('common.actions.save') : t('social.composer.send')"
          :title="editing ? t('common.actions.save') : t('social.composer.send')"
          data-testid="composer-send"
          @click="submit"
        >
          <SocialIcon :name="editing ? 'check' : 'send'" class="size-4" />
        </button>
      </div>
      <p class="mt-1 px-1 text-[10px] text-base-400">{{ t('social.composer.hint') }}</p>
    </template>
  </div>
</template>

<style scoped>
.composer {
  background: var(--color-base-850);
  box-shadow: inset 0 0 0 1px var(--color-base-700);
}
.composer:focus-within {
  box-shadow: inset 0 0 0 1px var(--color-redstone-500);
}
</style>
