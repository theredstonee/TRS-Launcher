<script setup lang="ts">
// Einwilligung vor der ersten Anfrage an die TRS API. Ohne Zustimmung sendet
// der Launcher nichts an den TRS-Server; ändern lässt sich das jederzeit unter
// Einstellungen → Datenschutz.
const trs = useTrsStore()
const toasts = useToasts()
const busy = ref(false)

async function decide(accepted: boolean) {
  if (busy.value) return
  busy.value = true
  try {
    await trs.setConsent(accepted)
    if (accepted) toasts.ok('TRS-Dienste sind eingeschaltet.')
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

function openPrivacy() {
  backend.openExternalUrl(TRS_PRIVACY_URL).catch((e) => toasts.error(e))
}
</script>

<template>
  <BaseDialog title="TRS-Dienste nutzen?" wide @close="trs.consentOpen = false">
    <div class="space-y-3 text-sm text-base-200">
      <p>
        Mit den TRS-Diensten bekommst du <strong class="text-base-50">TRS-Umhänge</strong>, eine
        <strong class="text-base-50">Freundesliste</strong> mit Online-Status und kannst Freunden auf ihren Server
        folgen. Dafür meldet sich der Launcher mit deinem Minecraft-Account beim TRS-Server
        (<span class="font-mono text-xs">api.theredstonee.de</span>) an – wie beim Beitritt zu einem Minecraft-Server.
        Dein Passwort und dein Minecraft-Token bekommt der Server nie.
      </p>
      <div class="rounded-lg border border-base-700 bg-base-850 px-3 py-2.5">
        <p class="mb-1.5 text-xs font-semibold text-base-50">Gespeichert werden</p>
        <ul class="list-disc space-y-0.5 pl-4 text-xs text-base-200">
          <li>deine Minecraft-UUID und dein Spielername</li>
          <li>gewählter Umhang, eingelöste Codes und hochgeladene Umhang-Bilder</li>
          <li>Freunde, Anfragen und Blockierungen</li>
          <li>deine Datenschutz-Einstellungen und Zeitpunkte (Anmeldung, Erstellung)</li>
          <li>
            der Online-Status (Launcher offen / im Spiel, Version, auf Wunsch der Server) – nur im Arbeitsspeicher,
            er verfällt nach 3 Minuten
          </li>
        </ul>
      </div>
      <p class="text-xs text-base-400">
        Du kannst die Dienste jederzeit unter <em>Einstellungen → Datenschutz</em> ausschalten und dort alle deine
        TRS-Daten löschen. Ohne Zustimmung sendet der Launcher nichts an den TRS-Server.
        <button class="text-redstone-300 hover:underline" @click="openPrivacy">Datenschutzerklärung lesen</button>
      </p>
    </div>
    <template #actions>
      <button class="btn btn-ghost" :disabled="busy" @click="decide(false)">Nein, danke</button>
      <button class="btn btn-primary" :disabled="busy" data-testid="trs-consent-accept" @click="decide(true)">
        {{ busy ? 'Einen Moment …' : 'TRS-Dienste nutzen' }}
      </button>
    </template>
  </BaseDialog>
</template>
