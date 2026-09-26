<script setup lang="ts">
import type { SocialToast } from '~/utils/socialToasts'
import { cornerClasses } from '~/utils/socialToasts'

// Benachrichtigungen aus „Sozial“: Gesicht, Name, Vorschau, Aktionen
// (Annehmen/Ablehnen, Beitreten) und Schnellantwort. Klick öffnet den Chat.
// Ecke, Dauer, Ton und Nicht stören stellt man unter Einstellungen ein.
const toasts = useSocialToasts()
const settings = useSettingsStore()

watch(
  () => settings.current?.social,
  (social) => toasts.setPrefs(social ?? null),
  { immediate: true, deep: true },
)

const replying = ref<number | null>(null)
const replyText = ref('')
const sending = ref(false)

function icon(toast: SocialToast) {
  switch (toast.kind) {
    case 'report':
      return 'flag' as const
    case 'moderation':
      return 'shield' as const
    case 'invite':
      return 'invite' as const
    default:
      return 'chat' as const
  }
}

function open(toast: SocialToast) {
  if (!toast.open) return
  toast.open()
  toasts.dismiss(toast.id)
}

async function act(toast: SocialToast, run: () => void | Promise<void>) {
  toasts.dismiss(toast.id)
  await run()
}

function startReply(toast: SocialToast) {
  replying.value = toast.id
  replyText.value = ''
  toasts.hold(toast.id, true)
}

async function sendReply(toast: SocialToast) {
  if (!toast.reply || !replyText.value.trim() || sending.value) return
  sending.value = true
  const ok = await toast.reply(replyText.value)
  sending.value = false
  if (ok) {
    replying.value = null
    toasts.dismiss(toast.id)
  }
}

function cancelReply(toast: SocialToast) {
  replying.value = null
  toasts.hold(toast.id, false)
}
</script>

<template>
  <div class="pointer-events-none fixed z-[65] flex w-80 flex-col gap-2" :class="cornerClasses(toasts.prefs.corner)" aria-live="polite" data-testid="social-toasts">
    <TransitionGroup name="social-toast">
      <div
        v-for="toast in toasts.items"
        :key="toast.id"
        class="social-toast pointer-events-auto w-80 overflow-hidden rounded-xl"
        role="status"
        @mouseenter="toasts.hold(toast.id, true)"
        @mouseleave="replying !== toast.id && toasts.hold(toast.id, false)"
      >
        <div class="flex gap-3 p-3" :class="{ 'cursor-pointer': toast.open }" @click="open(toast)">
          <span v-if="toast.face" class="block size-10 shrink-0 overflow-hidden rounded-md ring-1 ring-black/30">
            <PlayerFace :uuid="toast.face.uuid" :name="toast.face.name" />
          </span>
          <span v-else class="grid size-10 shrink-0 place-items-center rounded-md bg-redstone-900/50 text-redstone-300">
            <SocialIcon :name="icon(toast)" class="size-5" />
          </span>
          <span class="min-w-0 flex-1">
            <span class="flex items-center gap-2">
              <span class="truncate text-sm font-semibold text-base-50">{{ toast.title }}</span>
              <span v-if="toast.count > 1" class="badge shrink-0 bg-redstone-500 px-1.5 py-0 text-[10px] text-white">{{ toast.count }}</span>
            </span>
            <span class="line-clamp-2 text-xs text-base-200">{{ toast.body }}</span>
          </span>
          <button class="self-start text-base-400 hover:text-base-50" :aria-label="t('common.actions.close')" @click.stop="toasts.dismiss(toast.id)">
            <SocialIcon name="close" class="size-3.5" />
          </button>
        </div>

        <!-- Aktionen / Schnellantwort -->
        <div v-if="toast.actions.length || (toast.reply && toasts.prefs.quickReply)" class="flex flex-wrap gap-1.5 border-t border-base-700/70 px-3 py-2">
          <template v-if="replying === toast.id">
            <form class="flex w-full gap-1.5" @submit.prevent="sendReply(toast)">
              <input
                v-model="replyText"
                class="field min-w-0 flex-1 py-1 text-xs"
                maxlength="2000"
                :placeholder="t('social.toasts.replyPlaceholder')"
                :aria-label="t('social.toasts.replyPlaceholder')"
                autofocus
                @keydown.esc="cancelReply(toast)"
              />
              <button class="btn btn-primary px-2.5 py-1 text-xs" :disabled="sending || !replyText.trim()"><SocialIcon name="send" class="size-3.5" /></button>
            </form>
          </template>
          <template v-else>
            <button
              v-for="a in toast.actions"
              :key="a.label"
              class="btn px-2.5 py-1 text-xs"
              :class="a.primary ? 'btn-primary' : 'btn-ghost'"
              @click="act(toast, a.run)"
            >
              {{ a.label }}
            </button>
            <button v-if="toast.reply && toasts.prefs.quickReply" class="btn btn-ghost px-2.5 py-1 text-xs" @click="startReply(toast)">
              <SocialIcon name="reply" class="size-3.5" />{{ t('social.toasts.reply') }}
            </button>
          </template>
        </div>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.social-toast {
  background: color-mix(in srgb, var(--color-base-850) 96%, transparent);
  border: 1px solid var(--color-base-700);
  box-shadow:
    0 12px 32px -8px rgb(0 0 0 / 0.55),
    inset 0 1px 0 rgb(255 255 255 / 0.03),
    inset 3px 0 0 var(--color-redstone-500);
  backdrop-filter: blur(6px);
}
.social-toast-enter-active,
.social-toast-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.social-toast-enter-from,
.social-toast-leave-to {
  opacity: 0;
  transform: translateX(12px);
}
</style>
