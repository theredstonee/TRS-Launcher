<script setup lang="ts">
// Einstellungen → Über: Urheberhinweis (immer sichtbar, ohne Schalter), Lizenz und Drittanbieter.
// Additional terms under GPL-3.0 section 7(b) apply: see the file NOTICE.
const LICENSE_URL = 'https://github.com/theredstonee/TRS-Launcher/blob/main/LICENSE'
const NOTICE_URL = 'https://github.com/theredstonee/TRS-Launcher/blob/main/NOTICE'

const toasts = useToasts()
function open(url: string) {
  backend.openExternalUrl(url).catch((e) => toasts.error(e))
}
</script>

<template>
  <div data-testid="settings-about">
    <h3 class="mb-2 text-base font-semibold text-base-50">{{ t('settings.about.title') }}</h3>
    <p v-if="!authorNotice.intact" role="alert" class="mb-3 rounded-lg bg-redstone-600 px-4 py-3 text-sm font-medium text-white" data-testid="author-notice-warning">
      {{ authorNotice.warning }}
    </p>
    <p class="rounded-lg border border-base-700 bg-base-800 px-4 py-3 text-sm leading-6 text-base-50" data-testid="author-notice">{{ authorNotice.text }}</p>
    <p class="mt-4 text-sm leading-6 text-base-300">{{ t('settings.about.license') }}</p>
    <p class="mt-2 text-sm leading-6 text-base-300">{{ t('settings.about.thirdParty') }}</p>
    <div class="mt-4 flex flex-wrap gap-2">
      <button type="button" class="btn btn-ghost" @click="open(LICENSE_URL)">{{ t('settings.about.openLicense') }}</button>
      <button type="button" class="btn btn-ghost" @click="open(NOTICE_URL)">{{ t('settings.about.openNotice') }}</button>
    </div>
  </div>
</template>
