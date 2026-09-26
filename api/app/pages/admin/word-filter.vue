<script setup lang="ts">
// Wortfilter des Chats: lesen für das Team, pflegen nur Admins.
const { m, fill } = useLang()
const { a } = useAdminText()
const { api, can } = useAdmin()
const mod = computed(() => m.value.admin.mod)

const words = ref<FilterWord[]>([])
const form = reactive({ word: '', mode: 'word' as FilterWord['mode'], action: 'mask' as FilterWord['action'] })
const busy = ref('')
const error = ref('')
const loading = ref(true)
const q = ref('')

async function load() {
  try {
    words.value = (await api<{ words: FilterWord[] }>('/v1/admin/chat/word-filter')).words
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function add() {
  const word = form.word.trim()
  if (!word) return
  busy.value = 'add'
  error.value = ''
  try {
    await api('/v1/admin/chat/word-filter', { method: 'POST', body: { word: word.slice(0, 64), mode: form.mode, action: form.action } })
    form.word = ''
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}
async function remove(w: FilterWord) {
  busy.value = `w${w.id}`
  try {
    await api(`/v1/admin/chat/word-filter/${w.id}`, { method: 'DELETE' })
    words.value = words.value.filter((x) => x.id !== w.id)
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}
const shown = computed(() => (q.value.trim() ? words.value.filter((w) => w.word.includes(q.value.trim().toLowerCase())) : words.value))
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ mod.filterTitle }}</h1>
      <p class="adm-lead">{{ mod.filterLead }}</p>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div class="mt-6 grid gap-6" :class="can('wordfilter') ? 'lg:grid-cols-[22rem_1fr]' : ''">
      <form v-if="can('wordfilter')" class="card h-fit p-5" @submit.prevent="add">
        <label class="label" for="wf-word">{{ mod.word }}</label>
        <input id="wf-word" v-model="form.word" class="field" maxlength="64" required />
        <div class="mt-3 grid grid-cols-2 gap-2">
          <select v-model="form.mode" class="field" :aria-label="mod.mode.word">
            <option value="word">{{ mod.mode.word }}</option>
            <option value="contains">{{ mod.mode.contains }}</option>
          </select>
          <select v-model="form.action" class="field" :aria-label="mod.action.mask">
            <option value="mask">{{ mod.action.mask }}</option>
            <option value="block">{{ mod.action.block }}</option>
          </select>
        </div>
        <button type="submit" class="btn btn-primary mt-3 w-full" :disabled="busy === 'add' || !form.word.trim()">{{ mod.add }}</button>
      </form>
      <div class="min-w-0">
        <input v-model="q" class="field max-w-72" :placeholder="a.common.searchShort" :aria-label="a.common.searchShort" />
        <div v-if="loading" class="skeleton mt-3 h-40 rounded-xl" />
        <div v-else-if="!shown.length" class="adm-empty mt-3"><SiteIcon name="filter" class="size-6" />{{ mod.noWords }}</div>
        <ul v-else class="card mt-3 divide-y divide-base-800 text-sm">
          <li v-for="w in shown" :key="w.id" class="flex items-center gap-3 px-4 py-2">
            <span class="min-w-0 flex-1 truncate font-mono text-base-50">{{ w.word }}</span>
            <span class="text-xs text-base-400">{{ mod.mode[w.mode] }} · {{ mod.action[w.action] }}</span>
            <button v-if="can('wordfilter')" type="button" class="btn-icon size-7" :aria-label="mod.remove" :disabled="busy === `w${w.id}`" @click="remove(w)">
              <SiteIcon name="close" class="size-3.5" />
            </button>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>
