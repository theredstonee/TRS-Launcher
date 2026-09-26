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
    if (accepted) toasts.ok(t('trsConsent.enabled'))
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
  <BaseDialog :title="t('trsConsent.title')" wide @close="trs.consentOpen = false">
    <div class="space-y-3 text-sm text-base-200">
      <i18n-t keypath="trsConsent.intro" tag="p" scope="global">
        <template #capes><strong class="text-base-50">{{ t('trsConsent.capes') }}</strong></template>
        <template #friendsList><strong class="text-base-50">{{ t('trsConsent.friendsList') }}</strong></template>
        <template #host><span class="font-mono text-xs">{{ TRS_HOST }}</span></template>
      </i18n-t>
      <div class="rounded-lg border border-base-700 bg-base-850 px-3 py-2.5">
        <p class="mb-1.5 text-xs font-semibold text-base-50">{{ t('trsConsent.storedTitle') }}</p>
        <ul class="list-disc space-y-0.5 pl-4 text-xs text-base-200">
          <li>{{ t('trsConsent.stored.identity') }}</li>
          <li>{{ t('trsConsent.stored.capes') }}</li>
          <li>{{ t('trsConsent.stored.friends') }}</li>
          <li>{{ t('trsConsent.stored.chat') }}</li>
          <li>{{ t('trsConsent.stored.settings') }}</li>
          <li>{{ t('trsConsent.stored.sync') }}</li>
          <li>{{ t('trsConsent.stored.presence') }}</li>
        </ul>
      </div>
      <p class="text-xs text-base-400">
        <i18n-t keypath="trsConsent.footer" tag="span" scope="global">
          <template #path><em>{{ t('trsConsent.settingsPath') }}</em></template>
        </i18n-t>
        {{ ' ' }}
        <button class="text-redstone-300 hover:underline" @click="openPrivacy">{{ t('trsConsent.readPolicy') }}</button>
      </p>
    </div>
    <template #actions>
      <button class="btn btn-ghost" :disabled="busy" @click="decide(false)">{{ t('trsConsent.decline') }}</button>
      <button class="btn btn-primary" :disabled="busy" data-testid="trs-consent-accept" @click="decide(true)">
        {{ busy ? t('trsConsent.wait') : t('trsConsent.accept') }}
      </button>
    </template>
  </BaseDialog>
</template>
