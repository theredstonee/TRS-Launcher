<script setup lang="ts">
import { GROUP_COLOR_HEX, groupColors, groupNameSchema, MAX_GROUP_NAME, type ContentGroup, type GroupColor } from '~/utils/contentGroups'

// Gruppe im Inhalte-Tab anlegen oder bearbeiten: Name + Farbe.
const props = defineProps<{ group?: ContentGroup | null; count?: number }>()
const emit = defineEmits<{ close: []; save: [name: string, color: GroupColor] }>()

const name = ref(props.group?.name ?? '')
const color = ref<GroupColor>(props.group?.color ?? 'green')
const error = ref<string | null>(null)

function submit() {
  const parsed = groupNameSchema.safeParse(name.value)
  if (!parsed.success) {
    error.value = t('contentGroups.dialog.nameInvalid', { max: MAX_GROUP_NAME })
    return
  }
  emit('save', parsed.data, color.value)
}
</script>

<template>
  <BaseDialog :title="group ? t('contentGroups.dialog.editTitle') : t('contentGroups.dialog.createTitle')" @close="emit('close')">
    <form id="content-group" class="space-y-4" @submit.prevent="submit">
      <div>
        <label class="label" for="cg-name">{{ t('common.labels.name') }}</label>
        <input id="cg-name" v-model="name" class="field" :maxlength="MAX_GROUP_NAME" :placeholder="t('contentGroups.dialog.placeholder')" autofocus />
      </div>
      <div>
        <span class="label">{{ t('contentGroups.dialog.color') }}</span>
        <div class="flex flex-wrap gap-2" role="radiogroup" :aria-label="t('contentGroups.dialog.color')">
          <button
            v-for="c in groupColors"
            :key="c"
            type="button"
            role="radio"
            :aria-checked="color === c"
            :aria-label="t(`contentGroups.colors.${c}`)"
            :title="t(`contentGroups.colors.${c}`)"
            class="size-7 rounded-full ring-2 ring-offset-2 ring-offset-base-850 transition-transform hover:scale-110"
            :class="color === c ? 'ring-base-50' : 'ring-transparent'"
            :style="{ background: GROUP_COLOR_HEX[c] }"
            @click="color = c"
          />
        </div>
      </div>
      <p v-if="count" class="text-xs text-base-400">{{ t('contentGroups.dialog.assignHint', count) }}</p>
      <p v-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    </form>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button type="submit" form="content-group" class="btn btn-primary">{{ group ? t('common.actions.save') : t('contentGroups.dialog.create') }}</button>
    </template>
  </BaseDialog>
</template>
