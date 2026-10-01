<script setup lang="ts">
import type { DuplicateFile } from '~/types'
import { duplicatePrompt, settleDuplicateMods, type DuplicateChoice } from '~/utils/duplicateMods'

// Vor dem Start und nur, wenn dieselbe Mod mehrfach aktiv ist. Nichts wird gelöscht.
const prompt = duplicatePrompt

function fileLabel(file: DuplicateFile): string {
  return file.version ? `${file.fileName} (${file.version})` : file.fileName
}

function choose(choice: DuplicateChoice) {
  settleDuplicateMods(choice)
}
</script>

<template>
  <BaseDialog v-if="prompt" :title="t('content.duplicates.title')" wide @close="choose('cancel')">
    <p class="text-sm text-base-200">{{ t('content.duplicates.text') }}</p>
    <ul class="mt-3 max-h-48 space-y-1 overflow-y-auto text-xs text-base-300">
      <li v-for="group in prompt.groups" :key="group.id">
        {{
          t('content.duplicates.line', {
            name: group.name,
            keep: fileLabel(group.keep),
            files: group.disable.map(fileLabel).join(', '),
          })
        }}
      </li>
    </ul>
    <template #actions>
      <button class="btn btn-ghost" data-testid="duplicate-mods-cancel" @click="choose('cancel')">
        {{ t('common.actions.cancel') }}
      </button>
      <button class="btn btn-ghost" data-testid="duplicate-mods-anyway" @click="choose('anyway')">
        {{ t('content.duplicates.anyway') }}
      </button>
      <button class="btn btn-primary" data-testid="duplicate-mods-fix" @click="choose('fix')">
        {{ t('content.duplicates.fix') }}
      </button>
    </template>
  </BaseDialog>
</template>
