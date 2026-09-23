<script setup lang="ts">
import type { TrsPrivacy } from '~/utils/trs'

// Datenschutz der TRS-Dienste (Einstellungen → Datenschutz). Die Schalter
// liegen auf dem TRS-Server je Minecraft-Account; die Einwilligung selbst
// liegt nur lokal.
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
    toasts.ok('TRS-Dienste ausgeschaltet – der Launcher sendet nichts mehr an den TRS-Server.')
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
    toasts.ok('Alle TRS-Daten dieses Accounts sind gelöscht. Die TRS-Dienste sind jetzt aus.')
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
    <h3 class="section-heading">TRS-Dienste</h3>
    <p class="mb-2 text-xs text-base-400">
      Umhänge, Freunde und Online-Status über den TRS-Server.
      <button class="text-redstone-300 hover:underline" @click="openPrivacy">Was wird gespeichert?</button>
    </p>

    <SettingRow
      title="TRS-Dienste nutzen"
      description="Aus = der Launcher sendet nichts an den TRS-Server und meldet dich dort ab. Im Spiel nutzt der TRS Client die Dienste dann auch nicht."
    >
      <ToggleSwitch
        :model-value="trs.enabled"
        label="TRS-Dienste nutzen"
        :disabled="switching || !trs.status"
        @update:model-value="toggleServices"
      />
    </SettingRow>

    <template v-if="trs.enabled">
      <p v-if="!accounts.active" class="mt-2 text-xs text-base-400">Melde dich mit einem Minecraft-Account an, um diese Einstellungen zu ändern.</p>
      <p v-else-if="!settings" class="mt-2 text-xs text-base-400">
        {{ trs.problem === 'offline' ? 'Der TRS-Server ist gerade nicht erreichbar.' : 'Lade Einstellungen …' }}
      </p>
      <template v-else>
        <p class="mt-3 mb-1 text-[11px] text-base-600">Gilt für {{ trs.me?.name }} – jeder Account hat eigene Einstellungen.</p>
        <SettingRow title="TRS-Symbol zeigen" description="Andere TRS-Spieler sehen im Spiel, dass du TRS nutzt.">
          <ToggleSwitch
            :model-value="settings.showBadge"
            label="TRS-Symbol zeigen"
            :disabled="saving !== null"
            @update:model-value="update('showBadge', $event)"
          />
        </SettingRow>
        <SettingRow title="Umhang für andere sichtbar" description="Aus = nur du siehst deinen TRS-Umhang.">
          <ToggleSwitch
            :model-value="settings.showCapeToOthers"
            label="Umhang für andere sichtbar"
            :disabled="saving !== null"
            @update:model-value="update('showCapeToOthers', $event)"
          />
        </SettingRow>
        <SettingRow title="Online-Status" description="Wer sieht, ob du online bist und was du spielst.">
          <select
            class="field w-40 py-1.5"
            :value="settings.presenceVisibility"
            :disabled="saving !== null"
            aria-label="Online-Status sichtbar für"
            @change="update('presenceVisibility', ($event.target as HTMLSelectElement).value === 'nobody' ? 'nobody' : 'friends')"
          >
            <option value="friends">Freunde</option>
            <option value="nobody">Niemand</option>
          </select>
        </SettingRow>
        <SettingRow title="Server teilen" description="Freunde sehen, auf welchem Server du spielst, und können beitreten.">
          <ToggleSwitch
            :model-value="settings.shareServer"
            label="Server teilen"
            :disabled="saving !== null"
            @update:model-value="update('shareServer', $event)"
          />
        </SettingRow>

        <SettingRow
          title="Alle TRS-Daten löschen"
          description="Löscht dein TRS-Konto mit Umhängen, Uploads, Codes, Freunden und Online-Status sofort vom Server. Danach sind die TRS-Dienste aus."
          danger
          stacked
        >
          <button v-if="!confirmDelete" class="btn btn-danger" data-testid="trs-delete" @click="confirmDelete = true">Alle TRS-Daten löschen …</button>
          <div v-else class="rounded-lg border border-redstone-600/50 bg-redstone-900/30 px-3 py-3" role="alert">
            <p class="text-sm text-redstone-300">
              Wirklich alles von <strong>{{ trs.me?.name }}</strong> löschen? Freigeschaltete Umhänge und Freunde sind
              danach weg – das lässt sich nicht rückgängig machen.
            </p>
            <div class="mt-3 flex gap-2">
              <button class="btn btn-danger" :disabled="deleting" data-testid="trs-delete-confirm" @click="deleteAll">
                {{ deleting ? 'Lösche …' : 'Ja, endgültig löschen' }}
              </button>
              <button class="btn btn-ghost" :disabled="deleting" @click="confirmDelete = false">Abbrechen</button>
            </div>
          </div>
        </SettingRow>
      </template>
    </template>
  </div>
</template>
