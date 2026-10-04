<script setup lang="ts">
import type { Instance } from '~/types'
import { parseIconSource, type IconResult, type IconSource } from '~/utils/iconEditor'

// Symbol-Editor für eine bestehende Instanz: lädt die Editor-Daten (falls vorhanden) und speichert direkt.
const props = defineProps<{ instance: Pick<Instance, 'id' | 'name' | 'iconPath'> }>()
const emit = defineEmits<{ close: []; saved: [instance: Instance] }>()

const toasts = useToasts()
const loaded = ref(false)
const initial = ref<IconSource | null>(null)
const saving = ref(false)

onMounted(async () => {
  try {
    initial.value = parseIconSource(await backend.instanceIconSource(props.instance.id))
  } catch {
    initial.value = null
  } finally {
    loaded.value = true
  }
})

const hint = computed(() => (props.instance.iconPath && !initial.value ? t('iconEditor.replaceHint') : null))

async function save(result: IconResult) {
  saving.value = true
  try {
    const updated = await backend.saveInstanceIcon(props.instance.id, result.png, JSON.stringify(result.source))
    toasts.ok(t('iconEditor.saved'))
    emit('saved', updated)
    emit('close')
  } catch (e) {
    toasts.error(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <IconEditorDialog
    v-if="loaded"
    :initial="initial"
    :title="t('iconEditor.titleFor', { name: instance.name })"
    :hint="hint"
    :export-name="instance.name"
    :saving="saving"
    @close="emit('close')"
    @save="save"
  />
</template>
