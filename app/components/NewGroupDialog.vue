<script setup lang="ts">
import type { Instance } from '~/types'

// Neue eigene Gruppe: Name + welche Instanzen hinein.
const props = defineProps<{ instances: Instance[]; preselect?: string | null }>()
const emit = defineEmits<{ close: []; done: [] }>()

const name = ref('')
const chosen = ref<Set<string>>(new Set(props.preselect ? [props.preselect] : []))
const error = ref<string | null>(null)
const saving = ref(false)
const toasts = useToasts()

function toggle(id: string) {
  const next = new Set(chosen.value)
  if (!next.delete(id)) next.add(id)
  chosen.value = next
}

async function submit() {
  error.value = null
  const parsed = groupSchema.safeParse(name.value)
  if (!parsed.success || !parsed.data) {
    error.value = parsed.success ? 'Bitte einen Namen eingeben' : firstIssue(parsed.error)
    return
  }
  if (!chosen.value.size) {
    error.value = 'Wähle mindestens eine Instanz – leere Gruppen gibt es nicht.'
    return
  }
  saving.value = true
  try {
    for (const id of chosen.value) await backend.setInstanceGroup(id, parsed.data)
    toasts.ok(`Gruppe „${parsed.data}“ angelegt`)
    emit('done')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseDialog title="Neue Gruppe" @close="emit('close')">
    <form id="new-group" class="space-y-4" @submit.prevent="submit">
      <div>
        <label class="label" for="ng-name">Name</label>
        <input id="ng-name" v-model="name" class="field" maxlength="32" placeholder="z. B. PvP, Modpacks, Mit Freunden" autofocus />
      </div>
      <div>
        <span class="label">Instanzen</span>
        <ul class="-mr-2 max-h-64 space-y-1 overflow-y-auto pr-2">
          <li v-for="i in instances" :key="i.id">
            <label class="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-base-800">
              <input type="checkbox" class="size-4 accent-redstone-500" :checked="chosen.has(i.id)" @change="toggle(i.id)" />
              <InstanceIcon :instance="i" :size="28" />
              <span class="min-w-0 flex-1 truncate text-sm">{{ i.name }}</span>
              <span v-if="i.group" class="text-xs text-base-600">{{ i.group }}</span>
            </label>
          </li>
        </ul>
      </div>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">Abbrechen</button>
      <button type="submit" form="new-group" class="btn btn-primary" :disabled="saving">{{ saving ? 'Lege an …' : 'Gruppe anlegen' }}</button>
    </template>
  </BaseDialog>
</template>
