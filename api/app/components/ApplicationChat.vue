<script setup lang="ts">
import { MAX_APPLICATION_FILE_BYTES } from '~/utils/applicationChat'

interface ChatFile {
  id: string
  name: string
  bytes: number
  status: 'unscanned' | 'pending' | 'scanning' | 'clean' | 'infected' | 'failed' | 'released'
  signature: string | null
}
interface ChatMessage {
  id: string
  sender: { uuid: string; name: string | null }
  text: string
  createdAt: string
  files: ChatFile[]
}

const props = defineProps<{ applicationId: string; canReview?: boolean; canSend?: boolean }>()
const { account, load, api } = useAccount()
const { t, fill } = useTeamText()
const messages = ref<ChatMessage[]>([])
const pendingFiles = ref<ChatFile[]>([])
const text = ref('')
const error = ref('')
const busy = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

async function refresh() {
  if (!account.value) return
  try {
    messages.value = (await api<{ messages: ChatMessage[] }>(`/v1/me/applications/${props.applicationId}/messages`)).messages
  } catch (cause) {
    error.value = apiMessage(cause)
  }
}

async function upload(files: FileList | null) {
  if (!files?.length) return
  error.value = ''
  busy.value = true
  try {
    for (const file of Array.from(files)) {
      if (file.size > MAX_APPLICATION_FILE_BYTES) {
        error.value = t.value.myApps.chat.tooLarge
        continue
      }
      const result = await api<{ file: ChatFile }>(`/v1/me/applications/${props.applicationId}/attachments`, {
        method: 'POST',
        body: file,
        headers: { 'Content-Type': file.type || 'application/octet-stream', 'X-File-Name': encodeURIComponent(file.name) },
      })
      pendingFiles.value.push(result.file)
    }
  } catch (cause) {
    error.value = fill(t.value.myApps.chat.uploadFailed, { error: apiMessage(cause) })
  } finally {
    busy.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function send() {
  if (busy.value || (!text.value.trim() && !pendingFiles.value.length)) return
  error.value = ''
  busy.value = true
  try {
    await api(`/v1/me/applications/${props.applicationId}/messages`, {
      method: 'POST',
      body: { text: text.value, fileIds: pendingFiles.value.map(file => file.id) },
    })
    text.value = ''
    pendingFiles.value = []
    await refresh()
  } catch (cause) {
    error.value = fill(t.value.myApps.chat.sendFailed, { error: apiMessage(cause) })
  } finally {
    busy.value = false
  }
}

function fileStatus(file: ChatFile) {
  return file.status === 'unscanned' ? t.value.myApps.chat.unscanned : t.value.myApps.chat.unavailable
}

onMounted(async () => {
  await load()
  await refresh()
  timer = setInterval(refresh, 15_000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <section class="border-t border-base-800 pt-5" :aria-label="t.myApps.chat.title">
    <h2 class="section-title">{{ t.myApps.chat.title }}</h2>
    <div class="mt-3 max-h-96 space-y-3 overflow-y-auto overscroll-contain rounded-md border border-base-800 bg-base-950 p-3">
      <p v-if="!messages.length" class="py-4 text-center text-sm text-base-400">{{ t.myApps.chat.empty }}</p>
      <article v-for="message in messages" :key="message.id" class="w-fit max-w-[90%] rounded-md p-3" :class="message.sender.uuid === account?.uuid ? 'ml-auto bg-redstone-900/30' : 'mr-auto bg-base-850'">
        <p class="text-xs text-base-400">{{ message.sender.name ?? message.sender.uuid.slice(0, 8) }} · {{ new Date(message.createdAt).toLocaleString() }}</p>
        <p v-if="message.text" class="mt-1 whitespace-pre-line break-words text-sm text-base-100">{{ message.text }}</p>
        <ul v-if="message.files.length" class="mt-2 space-y-1">
          <li v-for="file in message.files" :key="file.id" class="text-sm">
            <a v-if="['unscanned', 'clean', 'released'].includes(file.status)" class="break-all text-redstone-300 hover:underline" :href="`/v1/me/applications/${applicationId}/files/${file.id}`" :download="file.name">{{ t.myApps.chat.download.replace('{name}', file.name) }} · {{ t.myApps.chat.unscanned }}</a>
            <span v-else class="min-w-0 wrap-break-word text-lamp-300">{{ file.name }} · {{ fileStatus(file) }}</span>
          </li>
        </ul>
      </article>
    </div>
    <ul v-if="pendingFiles.length" class="mt-2 space-y-1 text-xs text-base-300">
      <li v-for="file in pendingFiles" :key="file.id" class="flex flex-wrap justify-between gap-2">
        <span class="break-all">{{ file.name }}</span><span>{{ fileStatus(file) }}</span>
      </li>
    </ul>
    <form v-if="canSend !== false" class="mt-3 space-y-2" @submit.prevent="send">
      <textarea v-model="text" class="field min-h-20" maxlength="2000" :placeholder="t.myApps.chat.placeholder" :aria-label="t.myApps.chat.placeholder" />
      <input ref="fileInput" type="file" multiple class="sr-only" @change="upload(($event.target as HTMLInputElement).files)" />
      <div class="flex flex-wrap items-center justify-between gap-2">
        <button type="button" class="btn btn-ghost" :disabled="busy" @click="fileInput?.click()">{{ t.myApps.chat.attach }}</button>
        <button type="submit" class="btn btn-primary" :disabled="busy || (!text.trim() && !pendingFiles.length)">{{ busy ? t.myApps.chat.sending : t.myApps.chat.send }}</button>
      </div>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>
  </section>
</template>