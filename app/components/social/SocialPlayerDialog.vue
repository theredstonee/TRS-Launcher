<script setup lang="ts">
// „Freund hinzufügen“ bzw. „Spieler blockieren“ per Minecraft-Name oder UUID.
const props = defineProps<{ mode: 'add' | 'block' }>()
const emit = defineEmits<{ close: [] }>()
const trs = useTrsStore()
const toasts = useToasts()

const name = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

async function submit() {
  const parsed = trsTargetSchema.safeParse(name.value)
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  busy.value = true
  error.value = null
  try {
    if (props.mode === 'add') {
      const result = await backend.trs.friendRequest(parsed.data)
      toasts.ok(
        result.status === 'accepted'
          ? t('friends.toasts.nowFriends', { name: result.user.name })
          : t('friends.toasts.requestSent', { name: result.user.name }),
      )
      await trs.loadFriends()
    } else {
      const user = await backend.trs.block(parsed.data)
      toasts.ok(t('friends.toasts.blocked', { name: user.name }))
      await Promise.all([trs.loadBlocked(), trs.loadFriends()])
    }
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="mode === 'add' ? t('social.actions.addFriend') : t('social.actions.blockPlayer')" @close="emit('close')">
    <label class="label" for="social-player-name">{{ t('friends.addDialog.nameLabel') }}</label>
    <input
      id="social-player-name"
      v-model="name"
      class="field"
      maxlength="36"
      :placeholder="t('friends.addDialog.placeholder')"
      autocomplete="off"
      spellcheck="false"
      autofocus
      data-testid="player-name"
      @keydown.enter="submit"
    />
    <p class="mt-2 text-xs text-base-400">{{ mode === 'add' ? t('friends.addDialog.hint') : t('friends.blocked.hint') }}</p>
    <p v-if="error" role="alert" class="mt-2 text-xs text-redstone-300">{{ error }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button :class="mode === 'add' ? 'btn btn-primary' : 'btn btn-danger'" :disabled="busy || !name.trim()" data-testid="player-submit" @click="submit">
        {{ mode === 'add' ? (busy ? t('friends.addDialog.sending') : t('friends.addDialog.send')) : t('friends.list.block') }}
      </button>
    </template>
  </BaseDialog>
</template>
