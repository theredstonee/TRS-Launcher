<script setup lang="ts">
import type { FilterWord } from '~/utils/moderation'

// Wortfilter für den Chat: Liste für das ganze Team, Hinzufügen und Löschen nur
// für Admins. „Maskieren“ ersetzt das Wort durch *, „Blockieren“ lehnt die Nachricht ab.
const toasts = useToasts()
const team = useTeam()
const words = ref<FilterWord[] | null>(null)
const form = reactive({ word: '', mode: 'word' as FilterWord['mode'], action: 'mask' as FilterWord['action'] })
const error = ref<string | null>(null)
const busy = ref<string | null>(null)
const q = ref('')
const shown = computed(() => (words.value ?? []).filter((w) => !q.value.trim() || w.word.includes(q.value.trim().toLowerCase())))

async function load() {
  try {
    words.value = (await backend.social.adminWordFilter()).words
  } catch (e) {
    words.value = []
    toasts.error(e)
  }
}
onMounted(load)

async function add() {
  const word = form.word.trim()
  if (!word) return
  if ([...word].length < 2 || [...word].length > 48 || /\s/.test(word)) {
    error.value = t('admin.mod.wordRule')
    return
  }
  busy.value = 'add'
  error.value = null
  try {
    await backend.social.adminAddWord({ word, mode: form.mode, action: form.action })
    form.word = ''
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

async function remove(w: FilterWord) {
  busy.value = `w${w.id}`
  try {
    await backend.social.adminDeleteWord(w.id)
    words.value = (words.value ?? []).filter((x) => x.id !== w.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <section class="grid gap-5 lg:grid-cols-[20rem_minmax(0,1fr)]" :aria-label="t('team.nav.wordFilter')" data-testid="admin-word-filter">
    <div class="card h-fit p-4">
      <h2 class="section-title">{{ t('admin.mod.wordFilter') }}</h2>
      <p class="mt-1 text-xs text-base-400">{{ t('admin.mod.wordFilterHint') }}</p>
      <form v-if="team.isAdmin.value" class="mt-3 space-y-2" @submit.prevent="add">
        <input v-model="form.word" class="field" maxlength="48" :placeholder="t('admin.mod.word')" :aria-label="t('admin.mod.word')" data-testid="word-input" />
        <div class="grid grid-cols-2 gap-2">
          <select v-model="form.mode" class="field py-1.5 text-xs" :aria-label="t('admin.mod.mode.word')">
            <option value="word">{{ t('admin.mod.mode.word') }}</option>
            <option value="contains">{{ t('admin.mod.mode.contains') }}</option>
          </select>
          <select v-model="form.action" class="field py-1.5 text-xs" :aria-label="t('admin.mod.action.mask')">
            <option value="mask">{{ t('admin.mod.action.mask') }}</option>
            <option value="block">{{ t('admin.mod.action.block') }}</option>
          </select>
        </div>
        <button class="btn btn-primary w-full" :disabled="busy === 'add' || !form.word.trim()">{{ t('admin.mod.addWord') }}</button>
        <p v-if="error" role="alert" class="text-xs text-redstone-300">{{ error }}</p>
      </form>
      <p v-else class="mt-3 text-xs text-base-400">{{ t('team.common.readOnly') }}</p>
    </div>
    <div class="min-w-0">
      <input v-model="q" class="field mb-3 w-64 py-1.5 text-xs" :placeholder="t('team.wordFilter.search')" :aria-label="t('team.wordFilter.search')" />
      <div v-if="!words" class="skeleton h-40" />
      <p v-else-if="!shown.length" class="card px-4 py-6 text-center text-sm text-base-400">{{ t('admin.mod.noWords') }}</p>
      <ul v-else class="grid gap-1.5 sm:grid-cols-2 xl:grid-cols-3">
        <li v-for="w in shown" :key="w.id" class="card flex items-center gap-2 px-3 py-2 text-xs">
          <span class="min-w-0 flex-1 truncate font-mono text-sm text-base-50">{{ w.word }}</span>
          <span class="badge" :class="w.action === 'block' ? 'bg-redstone-900 text-redstone-300' : 'bg-base-800 text-base-200'">{{ t(`admin.mod.action.${w.action}`) }}</span>
          <span class="text-base-400">{{ t(`admin.mod.mode.${w.mode}`) }}</span>
          <button v-if="team.isAdmin.value" class="btn-icon size-6" :aria-label="t('common.actions.remove')" :disabled="busy === `w${w.id}`" @click="remove(w)">
            <SocialIcon name="close" class="size-3" />
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>
