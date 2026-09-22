<script setup lang="ts">
import type { Server } from '~/types'

const props = defineProps<{ server?: Server | null }>()
const emit = defineEmits<{ close: [] }>()

const servers = useServersStore()
const toasts = useToasts()

const name = ref(props.server?.name ?? '')
const address = ref(props.server?.address ?? '')
const autoResourcePack = ref(props.server?.autoResourcePack ?? true)
const error = ref<string | null>(null)
const busy = ref(false)

async function submit() {
  error.value = null
  const parsed = serverSchema.safeParse({
    name: name.value || address.value,
    address: address.value,
    autoResourcePack: autoResourcePack.value,
  })
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  busy.value = true
  try {
    if (props.server) await servers.update(props.server.id, parsed.data)
    else await servers.add(parsed.data)
    toasts.ok(props.server ? 'Server gespeichert' : 'Server hinzugefügt')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (!props.server) return
  busy.value = true
  try {
    await servers.remove(props.server.id)
    toasts.ok('Server entfernt')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="server ? 'Server bearbeiten' : 'Server hinzufügen'" @close="emit('close')">
    <form id="server-form" class="space-y-4" @submit.prevent="submit">
      <div>
        <label class="label" for="sv-address">Adresse</label>
        <input id="sv-address" v-model="address" class="field font-mono" maxlength="260" placeholder="play.example.de" spellcheck="false" autofocus />
      </div>
      <div>
        <label class="label" for="sv-name">Name</label>
        <input id="sv-name" v-model="name" class="field" maxlength="64" placeholder="Wie der Server heißen soll" />
      </div>
      <label class="flex items-start gap-2.5 text-sm text-base-200">
        <input v-model="autoResourcePack" type="checkbox" class="mt-0.5 accent-redstone-500" />
        <span>
          Server-Resourcepack automatisch annehmen
          <span class="block text-xs text-base-400">Damit Ränge, Icons und Sounds des Servers ohne Nachfrage laden.</span>
        </span>
      </label>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>

    <template #actions>
      <button v-if="server" type="button" class="btn btn-danger mr-auto" :disabled="busy" @click="remove">Entfernen</button>
      <button type="button" class="btn btn-ghost" @click="emit('close')">Abbrechen</button>
      <button type="submit" form="server-form" class="btn btn-primary" :disabled="busy">Speichern</button>
    </template>
  </BaseDialog>
</template>
