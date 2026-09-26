<script setup lang="ts">
// Server-Einladung verschicken: aus der eigenen Serverliste wählen oder eine
// Adresse eintippen. Der TRS-Server prüft Icon und Spielerzahl selbst.
const props = defineProps<{ conversationId: string }>()
const emit = defineEmits<{ close: [] }>()
const servers = useServersStore()
const chat = useChatStore()

const address = ref('')
const name = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

/** Wie der Kern: Hostname oder IPv4, optional mit Port. */
const ADDRESS = /^(?=.{1,253}(?::\d{1,5})?$)[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?(?::\d{1,5})?$/

onMounted(() => {
  if (!servers.loaded) servers.load().catch(() => {})
})

function pick(server: { name: string; address: string }) {
  address.value = server.address
  name.value = server.name.slice(0, 32)
}

async function send() {
  const a = address.value.trim()
  if (!ADDRESS.test(a) || a.includes('..')) {
    error.value = t('social.invite.invalidAddress')
    return
  }
  busy.value = true
  const ok = await chat.send(props.conversationId, { text: '', invite: { address: a.toLowerCase(), name: name.value.trim().slice(0, 32) || null } })
  busy.value = false
  if (ok) emit('close')
  else error.value = t('social.invite.failed')
}
</script>

<template>
  <BaseDialog :title="t('social.invite.title')" @close="emit('close')">
    <div v-if="servers.items.length" class="mb-4">
      <p class="label">{{ t('social.invite.fromServers') }}</p>
      <ul class="max-h-40 space-y-1 overflow-y-auto">
        <li v-for="s in servers.items" :key="s.id">
          <button
            class="flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm hover:bg-base-800"
            :class="{ 'bg-base-800': address === s.address }"
            @click="pick(s)"
          >
            <SocialIcon name="server" class="size-4 text-base-400" />
            <span class="min-w-0 flex-1 truncate text-base-50">{{ s.name }}</span>
            <span class="truncate font-mono text-xs text-base-400">{{ s.address }}</span>
          </button>
        </li>
      </ul>
    </div>
    <label class="label" for="invite-address">{{ t('social.invite.address') }}</label>
    <input id="invite-address" v-model="address" class="field font-mono" maxlength="261" placeholder="play.example.net" autocomplete="off" spellcheck="false" data-testid="invite-address" @keydown.enter="send" />
    <label class="label mt-3" for="invite-name">{{ t('social.invite.name') }}</label>
    <input id="invite-name" v-model="name" class="field" maxlength="32" :placeholder="t('social.invite.namePlaceholder')" @keydown.enter="send" />
    <p class="mt-2 text-xs text-base-400">{{ t('social.invite.hint') }}</p>
    <p v-if="error" role="alert" class="mt-2 text-xs text-redstone-300">{{ error }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="busy || !address.trim()" data-testid="invite-send" @click="send">{{ t('social.invite.send') }}</button>
    </template>
  </BaseDialog>
</template>
