<script setup lang="ts">
import type { InstanceServer } from '~/types'

// Server einer Instanz hinzufügen/bearbeiten. Launcher-Server (alle Instanzen)
// werden in der Launcher-Liste UND in der servers.dat dieser Instanz geändert;
// eigene Server nur in der servers.dat.
const props = defineProps<{ instanceId: string; server?: InstanceServer | null }>()
const emit = defineEmits<{ close: []; saved: [] }>()

const servers = useServersStore()
const toasts = useToasts()

const managed = computed(() => !!props.server?.launcherId)
const name = ref(props.server?.name ?? '')
const address = ref(props.server?.address ?? '')
const autoResourcePack = ref(props.server ? props.server.acceptTextures === true : true)
const everywhere = ref(false)
const error = ref<string | null>(null)
const busy = ref(false)
const confirmRemove = ref(false)

async function submit() {
  error.value = null
  const parsed = serverSchema.safeParse({ name: name.value || address.value, address: address.value, autoResourcePack: autoResourcePack.value })
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  busy.value = true
  try {
    const s = props.server
    if (!s) {
      if (everywhere.value) await servers.add(parsed.data)
      else await backend.addInstanceServer(props.instanceId, parsed.data)
      // Launcher-Server stehen bis zum nächsten Start nur „virtuell“ in der Liste – das reicht.
    } else {
      if (s.launcherId) await servers.update(s.launcherId, parsed.data)
      if (s.index !== null) await backend.updateInstanceServer(props.instanceId, s.index, s.address, parsed.data)
    }
    toasts.ok(s ? t('serverDialog.toasts.saved') : t('serverDialog.toasts.added'))
    emit('saved')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function remove() {
  const s = props.server
  if (!s) return
  busy.value = true
  try {
    if (s.launcherId) await servers.remove(s.launcherId)
    if (s.index !== null) await backend.removeInstanceServer(props.instanceId, s.index, s.address)
    toasts.ok(t('serverDialog.toasts.removed'))
    emit('saved')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
    confirmRemove.value = false
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="server ? t('serverDialog.editTitle') : t('serverDialog.addTitle')" @close="emit('close')">
    <form v-if="!confirmRemove" id="instance-server-form" class="space-y-4" @submit.prevent="submit">
      <div>
        <label class="label" for="isv-address">{{ t('serverDialog.address') }}</label>
        <input id="isv-address" v-model="address" class="field font-mono" maxlength="260" placeholder="play.example.de" spellcheck="false" autofocus />
      </div>
      <div>
        <label class="label" for="isv-name">{{ t('common.labels.name') }}</label>
        <input id="isv-name" v-model="name" class="field" maxlength="64" :placeholder="t('serverDialog.namePlaceholder')" />
      </div>
      <label class="flex items-start gap-2.5 text-sm text-base-200">
        <input v-model="autoResourcePack" type="checkbox" class="mt-0.5 accent-redstone-500" />
        <span>
          {{ t('serverDialog.autoResourcePack') }}
          <span class="block text-xs text-base-400">{{ t('serverDialog.autoResourcePackHint') }}</span>
        </span>
      </label>
      <label v-if="!server" class="flex items-start gap-2.5 text-sm text-base-200">
        <input v-model="everywhere" type="checkbox" class="mt-0.5 accent-redstone-500" />
        <span>
          {{ t('worlds.servers.everywhere') }}
          <span class="block text-xs text-base-400">{{ t('worlds.servers.everywhereHint') }}</span>
        </span>
      </label>
      <p v-if="managed" class="rounded-md border border-lamp-400/30 bg-lamp-900/40 px-3 py-2 text-xs text-lamp-300">{{ t('worlds.servers.managedHint') }}</p>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>
    <div v-else class="space-y-2 text-sm text-base-200">
      <p>{{ managed ? t('worlds.servers.removeManaged', { name: server!.name }) : t('worlds.servers.removeText', { name: server!.name }) }}</p>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </div>

    <template #actions>
      <template v-if="!confirmRemove">
        <button v-if="server" type="button" class="btn btn-danger mr-auto" :disabled="busy" @click="confirmRemove = true">{{ t('common.actions.remove') }}</button>
        <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
        <button type="submit" form="instance-server-form" class="btn btn-primary" :disabled="busy">{{ t('common.actions.save') }}</button>
      </template>
      <template v-else>
        <button type="button" class="btn btn-ghost" @click="confirmRemove = false">{{ t('common.actions.back') }}</button>
        <button type="button" class="btn btn-danger" :disabled="busy" @click="remove">{{ t('common.actions.remove') }}</button>
      </template>
    </template>
  </BaseDialog>
</template>
