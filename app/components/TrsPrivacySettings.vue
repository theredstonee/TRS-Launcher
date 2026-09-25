<script setup lang="ts">
import type { TrsPrivacy } from '~/utils/trs'

// Datenschutz der TRS-Dienste (Einstellungen → Datenschutz). Die Schalter
// liegen auf dem TRS-Server je Minecraft-Account; die Einwilligung selbst
// liegt nur lokal. „Mit TRS-Konto synchronisieren“ ist eine lokale Einstellung
// (`trsSync`) und wird vom Einstellungsfenster mitgespeichert.
const sync = defineModel<boolean>('sync', { default: true })
const trs = useTrsStore()
const accounts = useAccountsStore()
const toasts = useToasts()

const saving = ref<keyof TrsPrivacy | null>(null)
const confirmDelete = ref(false)
const deleting = ref(false)
const switching = ref(false)

const settings = computed(() => trs.me?.settings ?? null)

onMounted(async () => {
  if (!trs.status) await trs.refreshStatus()
  if (trs.enabled && !trs.me) await trs.loadMe()
})

async function toggleServices(on: boolean) {
  if (on) {
    // Einschalten nur mit dem Hinweis, was gespeichert wird.
    trs.askConsent()
    return
  }
  switching.value = true
  try {
    await trs.setConsent(false)
    toasts.ok(t('trsPrivacy.turnedOff'))
  } catch (e) {
    toasts.error(e)
  } finally {
    switching.value = false
  }
}

async function update<K extends keyof TrsPrivacy>(key: K, value: TrsPrivacy[K]) {
  if (!trs.me || saving.value) return
  saving.value = key
  try {
    trs.me = await backend.trs.updateMe({ [key]: value } as Partial<TrsPrivacy>)
  } catch (e) {
    toasts.error(e)
  } finally {
    saving.value = null
  }
}

async function deleteAll() {
  deleting.value = true
  try {
    await trs.deleteAll()
    confirmDelete.value = false
    toasts.ok(t('trsPrivacy.delete.done'))
  } catch (e) {
    toasts.error(e)
  } finally {
    deleting.value = false
  }
}

function openPrivacy() {
  backend.openExternalUrl(TRS_PRIVACY_URL).catch((e) => toasts.error(e))
}
</script>

<template>
  <div class="mt-6">
    <h3 class="section-heading">{{ t('trsPrivacy.heading') }}</h3>
    <p class="mb-2 text-xs text-base-400">
      {{ t('trsPrivacy.intro') }}
      <button class="text-redstone-300 hover:underline" @click="openPrivacy">{{ t('trsPrivacy.whatStored') }}</button>
    </p>

    <SettingRow :title="t('trsPrivacy.services.title')" :description="t('trsPrivacy.services.description')">
      <ToggleSwitch
        :model-value="trs.enabled"
        :label="t('trsPrivacy.services.title')"
        :disabled="switching || !trs.status"
        @update:model-value="toggleServices"
      />
    </SettingRow>

    <template v-if="trs.enabled">
      <SettingRow :title="t('trsPrivacy.sync.title')" :description="t('trsPrivacy.sync.description')">
        <ToggleSwitch v-model="sync" :label="t('trsPrivacy.sync.title')" data-testid="trs-sync-toggle" />
      </SettingRow>
      <p v-if="!accounts.active" class="mt-2 text-xs text-base-400">{{ t('trsPrivacy.noAccount') }}</p>
      <p v-else-if="!settings" class="mt-2 text-xs text-base-400">
        {{ trs.problem === 'offline' ? t('trsPrivacy.offline') : t('trsPrivacy.loading') }}
      </p>
      <template v-else>
        <p class="mt-3 mb-1 text-[11px] text-base-600">{{ t('trsPrivacy.perAccount', { name: trs.me?.name ?? '' }) }}</p>
        <SettingRow :title="t('trsPrivacy.badge.title')" :description="t('trsPrivacy.badge.description')">
          <ToggleSwitch
            :model-value="settings.showBadge"
            :label="t('trsPrivacy.badge.title')"
            :disabled="saving !== null"
            @update:model-value="update('showBadge', $event)"
          />
        </SettingRow>
        <SettingRow :title="t('trsPrivacy.cape.title')" :description="t('trsPrivacy.cape.description')">
          <ToggleSwitch
            :model-value="settings.showCapeToOthers"
            :label="t('trsPrivacy.cape.title')"
            :disabled="saving !== null"
            @update:model-value="update('showCapeToOthers', $event)"
          />
        </SettingRow>
        <SettingRow :title="t('trsPrivacy.presence.title')" :description="t('trsPrivacy.presence.description')">
          <select
            class="field w-40 py-1.5"
            :value="settings.presenceVisibility"
            :disabled="saving !== null"
            :aria-label="t('trsPrivacy.presence.label')"
            @change="update('presenceVisibility', ($event.target as HTMLSelectElement).value === 'nobody' ? 'nobody' : 'friends')"
          >
            <option value="friends">{{ t('trsPrivacy.presence.friends') }}</option>
            <option value="nobody">{{ t('trsPrivacy.presence.nobody') }}</option>
          </select>
        </SettingRow>
        <SettingRow :title="t('trsPrivacy.server.title')" :description="t('trsPrivacy.server.description')">
          <ToggleSwitch
            :model-value="settings.shareServer"
            :label="t('trsPrivacy.server.title')"
            :disabled="saving !== null"
            @update:model-value="update('shareServer', $event)"
          />
        </SettingRow>

        <SettingRow v-if="trs.isAdmin" :title="t('webLogin.title')" :description="t('webLogin.settingsDescription', { host: TRS_HOST })">
          <button class="btn btn-ghost" data-testid="settings-web-login" @click="trs.openWebLogin()">{{ t('webLogin.open') }}</button>
        </SettingRow>

        <SettingRow
          :title="t('trsPrivacy.delete.title')"
          :description="t('trsPrivacy.delete.description')"
          danger
          stacked
        >
          <button v-if="!confirmDelete" class="btn btn-danger" data-testid="trs-delete" @click="confirmDelete = true">{{ t('trsPrivacy.delete.button') }}</button>
          <div v-else class="rounded-lg border border-redstone-600/50 bg-redstone-900/30 px-3 py-3" role="alert">
            <i18n-t keypath="trsPrivacy.delete.confirm" tag="p" scope="global" class="text-sm text-redstone-300">
              <template #name><strong>{{ trs.me?.name }}</strong></template>
            </i18n-t>
            <div class="mt-3 flex gap-2">
              <button class="btn btn-danger" :disabled="deleting" data-testid="trs-delete-confirm" @click="deleteAll">
                {{ deleting ? t('trsPrivacy.delete.deleting') : t('trsPrivacy.delete.confirmButton') }}
              </button>
              <button class="btn btn-ghost" :disabled="deleting" @click="confirmDelete = false">{{ t('common.actions.cancel') }}</button>
            </div>
          </div>
        </SettingRow>
      </template>
    </template>
  </div>
</template>
