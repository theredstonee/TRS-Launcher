<script setup lang="ts">
import type { SocialSettings } from '~/types'
import { playNotificationSound } from '~/utils/sound'

// Einstellungen → Benachrichtigungen: Ecke, Dauer (3–10 s), Ton, Nicht stören
// (von Hand + automatisch bei Vollbild), Windows-Benachrichtigung, Arten.
const model = defineModel<SocialSettings>({ required: true })
const corners: SocialSettings['corner'][] = ['top-right', 'top-left', 'bottom-right', 'bottom-left']
const types = ['messages', 'invites', 'friendRequests', 'capeOffers', 'friendOnline'] as const

function preview() {
  void useSocialToasts().notify('report', {
    key: 'preview',
    title: t('settings.notifications.previewTitle'),
    body: t('settings.notifications.previewBody'),
    face: null,
    actions: [],
  })
}

function set<K extends keyof SocialSettings>(key: K, value: SocialSettings[K]) {
  model.value = { ...model.value, [key]: value }
  if (key === 'sound' && value) playNotificationSound()
}
</script>

<template>
  <div>
    <h3 class="section-heading">{{ t('settings.notifications.title') }}</h3>
    <p class="mb-2 text-xs text-base-400">{{ t('settings.notifications.intro') }}</p>

    <SettingRow :title="t('settings.notifications.toastsTitle')" :description="t('settings.notifications.toastsDescription')">
      <ToggleSwitch :model-value="model.toasts" :label="t('settings.notifications.toastsTitle')" @update:model-value="(v: boolean) => set('toasts', v)" />
    </SettingRow>

    <SettingRow :title="t('settings.notifications.dndTitle')" :description="t('settings.notifications.dndDescription')">
      <ToggleSwitch :model-value="model.doNotDisturb" :label="t('settings.notifications.dndTitle')" @update:model-value="(v: boolean) => set('doNotDisturb', v)" />
    </SettingRow>

    <SettingRow :title="t('settings.notifications.fullscreenTitle')" :description="t('settings.notifications.fullscreenDescription')">
      <ToggleSwitch :model-value="model.quietInFullscreen" :label="t('settings.notifications.fullscreenTitle')" @update:model-value="(v: boolean) => set('quietInFullscreen', v)" />
    </SettingRow>

    <SettingRow :title="t('settings.notifications.cornerTitle')" stacked>
      <div class="grid w-56 grid-cols-2 gap-2" role="radiogroup" :aria-label="t('settings.notifications.cornerTitle')">
        <button
          v-for="c in corners"
          :key="c"
          class="rounded-lg border px-3 py-2 text-left text-xs transition-colors"
          :class="model.corner === c ? 'border-redstone-500 bg-redstone-900/40 text-base-50' : 'border-base-700 text-base-200 hover:border-base-600'"
          role="radio"
          :aria-checked="model.corner === c"
          @click="set('corner', c)"
        >
          {{ t(`settings.notifications.corners.${c}`) }}
        </button>
      </div>
    </SettingRow>

    <SettingRow :title="t('settings.notifications.durationTitle')" :description="t('settings.notifications.durationValue', { seconds: model.durationSecs })">
      <input
        type="range"
        min="3"
        max="10"
        step="1"
        class="w-40 accent-redstone-500"
        :value="model.durationSecs"
        :aria-label="t('settings.notifications.durationTitle')"
        @input="(e) => set('durationSecs', Number((e.target as HTMLInputElement).value))"
      />
    </SettingRow>

    <SettingRow :title="t('settings.notifications.soundTitle')" :description="t('settings.notifications.soundDescription')">
      <ToggleSwitch :model-value="model.sound" :label="t('settings.notifications.soundTitle')" @update:model-value="(v: boolean) => set('sound', v)" />
    </SettingRow>

    <SettingRow :title="t('settings.notifications.nativeTitle')" :description="t('settings.notifications.nativeDescription')">
      <ToggleSwitch :model-value="model.native" :label="t('settings.notifications.nativeTitle')" @update:model-value="(v: boolean) => set('native', v)" />
    </SettingRow>

    <SettingRow :title="t('settings.notifications.quickReplyTitle')" :description="t('settings.notifications.quickReplyDescription')">
      <ToggleSwitch :model-value="model.quickReply" :label="t('settings.notifications.quickReplyTitle')" @update:model-value="(v: boolean) => set('quickReply', v)" />
    </SettingRow>

    <h3 class="section-heading mt-6">{{ t('settings.notifications.typesTitle') }}</h3>
    <SettingRow v-for="k in types" :key="k" :title="t(`settings.notifications.types.${k}`)">
      <ToggleSwitch :model-value="model[k]" :label="t(`settings.notifications.types.${k}`)" :disabled="!model.toasts" @update:model-value="(v: boolean) => set(k, v)" />
    </SettingRow>

    <button class="btn btn-ghost mt-4" data-testid="notification-preview" @click="preview">
      <SocialIcon name="bell" class="size-4" />{{ t('settings.notifications.preview') }}
    </button>
  </div>
</template>
