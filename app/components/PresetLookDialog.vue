<script setup lang="ts">
// Symbol + Akzentfarbe eines eigenen Presets wählen (sichtbar in der Preset-Liste).
const props = defineProps<{ icon: string; color: string; name: string }>()
const emit = defineEmits<{ close: []; pick: [look: { icon: string; color: string }] }>()

const icon = ref(props.icon)
const color = ref(props.color)
</script>

<template>
  <BaseDialog :title="t('presets.look.title')" @close="emit('close')">
    <div class="mb-4 flex items-center gap-3">
      <PresetBadge :icon="icon" :color="color" :size="56" />
      <div class="min-w-0">
        <p class="truncate font-medium">{{ name || t('presets.editor.namePlaceholder') }}</p>
        <p class="text-xs text-base-400">{{ t('presets.look.hint') }}</p>
      </div>
    </div>

    <h3 class="label">{{ t('presets.look.icon') }}</h3>
    <div class="mb-4 grid grid-cols-10 gap-1.5 mobile:grid-cols-5" role="radiogroup" :aria-label="t('presets.look.icon')">
      <button
        v-for="ic in presetIconChoices"
        :key="ic"
        type="button"
        role="radio"
        :aria-checked="icon === ic"
        :aria-label="t(`presets.look.icons.${ic}`)"
        :title="t(`presets.look.icons.${ic}`)"
        class="grid aspect-square place-items-center rounded-md ring-1 transition-colors mobile:min-h-11"
        :class="icon === ic ? 'bg-base-700 text-base-50 ring-redstone-500' : 'bg-base-900 text-base-400 ring-base-800 hover:text-base-50'"
        @click="icon = ic"
      >
        <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="presetIconPath(ic)" /></svg>
      </button>
    </div>

    <h3 class="label">{{ t('presets.look.color') }}</h3>
    <div class="flex flex-wrap gap-2" role="radiogroup" :aria-label="t('presets.look.color')">
      <button
        v-for="c in presetColorChoices"
        :key="c"
        type="button"
        role="radio"
        :aria-checked="color === c"
        :aria-label="t(`presets.look.colors.${c}`)"
        :title="t(`presets.look.colors.${c}`)"
        class="size-8 rounded-full ring-2 ring-offset-2 ring-offset-base-850 transition-transform hover:scale-110 mobile:size-11"
        :class="color === c ? 'ring-base-50' : 'ring-transparent'"
        :style="{ background: presetColorHex[c] }"
        @click="color = c"
      />
    </div>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button type="button" class="btn btn-primary" @click="emit('pick', { icon, color })">{{ t('common.actions.apply') }}</button>
    </template>
  </BaseDialog>
</template>
