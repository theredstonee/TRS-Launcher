<script setup lang="ts">
import type { PackPreview } from '~/types'
import type { SharedPack } from '~/utils/packs'

// „Modpack per Code“: Code (oder Link) eingeben, Pack prüfen lassen, ansehen
// und installieren. Eigene Mod-Dateien im Pack (nicht von Modrinth) brauchen
// ein Häkchen „ich vertraue …“. Wie bei Modrinth-Packs: mit oder ohne TRS Client.
const props = defineProps<{ initialCode: string }>()
const emit = defineEmits<{ close: [] }>()

const trs = useTrsStore()
const packs = usePacksStore()
const tasks = useTasksStore()
const input = ref(props.initialCode)
const loading = ref(false)
const error = ref<string | null>(null)
const pack = ref<SharedPack | null>(null)
const preview = ref<PackPreview | null>(null)
const trusted = ref(false)
const choice = ref(true)
const reporting = ref(false)

const offer = computed(() => preview.value?.trsClient ?? null)
const showChoice = computed(() => trsShowsChoice(offer.value))
const normalized = computed(() => normalizePackCode(input.value))
const canInstall = computed(() => !!pack.value && (!pack.value.ownJars || trusted.value))

async function check() {
  const code = normalized.value
  if (!code) {
    error.value = t('packs.code.invalid')
    return
  }
  loading.value = true
  error.value = null
  try {
    const r = await backend.packs.previewCode(code)
    pack.value = r.pack
    preview.value = r.preview
    choice.value = trsAfterPreview(r.preview.trsClient, false, true)
    trusted.value = false
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (normalizePackCode(props.initialCode)) void check()
})

function install() {
  const p = pack.value
  if (!p || !canInstall.value) return
  const trsClient = trsRequest(offer.value, choice.value)
  void tasks.run(
    { key: taskKey('packcode', p.code), kind: 'modpack', title: p.name, stage: packStageLabel('pack'), cancellable: true },
    async (ctx) => {
      const instance = await backend.packs.installCode(p.code, trsClient, (pr) => ctx.progress(packPercent(pr), packStageLabel(pr.phase)), ctx.taskId)
      ctx.update({ instanceId: instance.id, doneText: t('tasks.toast.modpackReady', { name: instance.name }) })
      await Promise.allSettled([useInstancesStore().load(), packs.loadLinks()])
      if (packs.inbox.some((x) => x.pack.id === p.id)) void packs.dismiss(p.id)
      return instance
    },
  )
  emit('close')
}
</script>

<template>
  <BaseDialog :title="t('packs.code.title')" @close="emit('close')">
    <div v-if="!trs.enabled" class="rounded-md border border-base-700 bg-base-900 px-3 py-2 text-sm text-base-300">{{ t('packs.needsTrs') }}</div>
    <template v-else-if="!pack">
      <p class="mb-3 text-sm text-base-200">{{ t('packs.code.intro') }}</p>
      <form class="flex gap-2" @submit.prevent="check">
        <input
          v-model="input"
          class="field display flex-1 tracking-wider uppercase"
          maxlength="120"
          placeholder="TRS-XXXX-XXXX"
          spellcheck="false"
          autocomplete="off"
          :aria-label="t('packs.code.label')"
          :disabled="loading"
          data-testid="pack-code-input"
        />
        <button type="submit" class="btn btn-primary" :disabled="loading || !input.trim()">{{ loading ? t('packs.code.checking') : t('packs.code.check') }}</button>
      </form>
      <p v-if="loading" class="mt-2 text-xs text-base-400">{{ t('packs.code.loadingHint') }}</p>
      <p v-if="error" role="alert" class="mt-2 text-sm text-redstone-300">{{ error }}</p>
    </template>

    <template v-else>
      <div class="flex items-start gap-3">
        <span class="mt-0.5 block size-9 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="pack.owner.uuid" :name="pack.owner.name" /></span>
        <div class="min-w-0 flex-1">
          <p class="truncate font-medium text-base-50">{{ pack.name }} <span class="text-xs text-base-400">· {{ pack.packVersion }}</span></p>
          <p class="text-xs text-base-400">{{ t('packs.code.by', { name: pack.owner.name }) }} · {{ packVersionLine(pack) }}</p>
        </div>
      </div>
      <p v-if="pack.summary" class="mt-3 text-sm text-base-200">{{ pack.summary }}</p>
      <ul class="mt-3 space-y-1 text-xs text-base-300">
        <li>{{ t('packs.code.mods', pack.modrinthFiles) }}</li>
        <li v-if="pack.otherFiles">{{ t('packs.code.files', pack.otherFiles) }}</li>
        <li>{{ formatBytes(pack.bytes) }} · {{ packExpiry(pack.expiresAt) }}</li>
      </ul>
      <div v-if="pack.ownJars" class="mt-3 rounded-md border border-lamp-400/40 bg-base-900 px-3 py-2">
        <p class="text-sm text-lamp-300">{{ t('packs.code.ownJars', pack.ownJars) }}</p>
        <label class="mt-2 flex cursor-pointer items-start gap-2 text-xs text-base-200">
          <input v-model="trusted" type="checkbox" class="mt-0.5 size-4 accent-redstone-500" data-testid="pack-trust" />
          {{ t('packs.code.trust', { name: pack.owner.name }) }}
        </label>
      </div>
      <template v-if="showChoice">
        <h3 class="mt-4 mb-1.5 text-xs font-medium tracking-wide text-base-400 uppercase">{{ t('trsChoice.title') }}</h3>
        <TrsClientChoice v-model="choice" :offer="offer" :loading="false" :failed="false" />
      </template>
    </template>

    <template #actions>
      <template v-if="pack">
        <button class="btn btn-ghost mr-auto text-xs" @click="reporting = true">{{ t('packs.code.report') }}</button>
        <button class="btn btn-ghost" @click="pack = null; preview = null">{{ t('common.actions.back') }}</button>
        <button class="btn btn-primary" :disabled="!canInstall" @click="install">{{ t('common.actions.install') }}</button>
      </template>
      <button v-else class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
    </template>
  </BaseDialog>
  <SocialReportDialog v-if="reporting && pack" :target="{ kind: 'pack', packId: pack.id }" :label="pack.name" @close="reporting = false" />
</template>
