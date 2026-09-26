<script setup lang="ts">
// Codes: Liste (für alle im Team), anlegen und widerrufen nur für Admins.
const { m, fill } = useLang()
const { a } = useAdminText()
const { api, can } = useAdmin()

interface CodeView {
  id: number
  hint: string
  capeId: string | null
  cosmeticId: string | null
  maxUses: number
  uses: number
  expiresAt: string | null
  revokedAt: string | null
  note: string | null
  createdAt: string
}
const codes = ref<CodeView[]>([])
const catalog = ref<SiteCape[]>([])
const lockedCapes = computed(() => catalog.value.filter((c) => c.unlock !== 'free'))
const form = reactive({ capeId: '', count: 1, maxUses: 1, note: '' })
const created = ref<string[]>([])
const busy = ref('')
const error = ref('')
const copied = ref(false)
const revoking = ref<CodeView | null>(null)
const loading = ref(true)

async function load() {
  error.value = ''
  try {
    const [r, capes] = await Promise.all([api<{ codes: CodeView[] }>('/v1/admin/codes'), api<{ capes: SiteCape[] }>('/v1/site/capes')])
    codes.value = r.codes
    catalog.value = capes.capes
    if (!form.capeId && lockedCapes.value.length) form.capeId = lockedCapes.value[0]!.id
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function createCodes() {
  busy.value = 'codes'
  error.value = ''
  try {
    const r = await api<{ codes: (CodeView & { code: string })[] }>('/v1/admin/codes', {
      method: 'POST',
      body: {
        capeId: form.capeId,
        count: Math.min(100, Math.max(1, Math.round(form.count))),
        maxUses: Math.min(100000, Math.max(1, Math.round(form.maxUses))),
        ...(form.note.trim() ? { note: form.note.trim().slice(0, 200) } : {}),
      },
    })
    created.value = r.codes.map((c) => c.code)
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}

async function revoke() {
  const c = revoking.value
  if (!c) return
  busy.value = `code-${c.id}`
  try {
    await api(`/v1/admin/codes/${c.id}`, { method: 'DELETE' })
    revoking.value = null
    await load()
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}

async function copyCodes() {
  try {
    await navigator.clipboard.writeText(created.value.join('\n'))
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // Codes stehen markierbar in der Liste.
  }
}
const capeName = (id: string | null) => catalog.value.find((c) => c.id === id)?.name ?? id
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.nav.codes }}</h1>
      <p v-if="!can('codes')" class="adm-lead">{{ a.nav.adminOnly }}</p>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div class="mt-6 grid gap-6" :class="can('codes') ? 'lg:grid-cols-[22rem_1fr]' : ''">
      <form v-if="can('codes')" class="card h-fit p-5" @submit.prevent="createCodes">
        <h2 class="section-title">{{ m.admin.codes.create }}</h2>
        <label class="label mt-4" for="code-cape">{{ m.admin.codes.cape }}</label>
        <select id="code-cape" v-model="form.capeId" class="field" required>
          <option v-for="c in lockedCapes" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <div class="mt-3 grid grid-cols-2 gap-3">
          <div>
            <label class="label" for="code-count">{{ m.admin.codes.count }}</label>
            <input id="code-count" v-model.number="form.count" type="number" min="1" max="100" class="field" required />
          </div>
          <div>
            <label class="label" for="code-uses">{{ m.admin.codes.uses }}</label>
            <input id="code-uses" v-model.number="form.maxUses" type="number" min="1" max="100000" class="field" required />
          </div>
        </div>
        <label class="label mt-3" for="code-note">{{ m.admin.codes.note }}</label>
        <input id="code-note" v-model="form.note" class="field" maxlength="200" />
        <button type="submit" class="btn btn-primary mt-4 w-full" :disabled="busy === 'codes' || !form.capeId">{{ m.admin.codes.create }}</button>
        <div v-if="created.length" class="mt-5 border-t border-base-800 pt-4">
          <p class="text-xs text-lamp-300">{{ m.admin.codes.created }}</p>
          <pre class="codes-out mt-2 select-all">{{ created.join('\n') }}</pre>
          <button type="button" class="btn btn-ghost mt-2 w-full" @click="copyCodes">
            <SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />{{ copied ? m.common.copied : m.common.copy }}
          </button>
        </div>
      </form>

      <div class="min-w-0">
        <div v-if="loading" class="skeleton h-40 rounded-xl" />
        <div v-else-if="!codes.length" class="adm-empty"><SiteIcon name="ticket" class="size-6" />{{ m.admin.codes.none }}</div>
        <div v-else class="card overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead class="border-b border-base-800 text-xs text-base-400">
              <tr><th class="px-4 py-2.5">…</th><th class="px-4 py-2.5">{{ m.admin.codes.cape }}</th><th class="px-4 py-2.5" /><th class="px-4 py-2.5">{{ m.admin.codes.note }}</th><th /></tr>
            </thead>
            <tbody class="divide-y divide-base-800">
              <tr v-for="c in codes" :key="c.id" :class="{ 'opacity-45': c.revokedAt || c.uses >= c.maxUses }">
                <td class="px-4 py-2.5 font-mono text-base-50">…{{ c.hint }}</td>
                <td class="px-4 py-2.5">{{ c.capeId ? capeName(c.capeId) : c.cosmeticId }}</td>
                <td class="px-4 py-2.5 text-base-400 tabular-nums">{{ fill(m.admin.codes.uses2, { uses: c.uses, max: c.maxUses }) }}</td>
                <td class="max-w-56 truncate px-4 py-2.5 text-base-400">{{ c.note }}</td>
                <td class="px-4 py-2.5 text-right">
                  <button v-if="!c.revokedAt && can('codes')" type="button" class="btn btn-danger py-1 text-xs" :disabled="busy === `code-${c.id}`" @click="revoking = c">{{ m.admin.codes.revoke }}</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
    <AdminConfirm v-if="revoking" :title="m.admin.codes.revoke" :text="`…${revoking.hint}`" danger :busy="busy !== ''" @cancel="revoking = null" @confirm="revoke" />
  </div>
</template>

<style scoped>
.codes-out {
  max-height: 12rem;
  overflow: auto;
  padding: 0.75rem;
  border-radius: 0.375rem;
  font-family: var(--font-mono);
  font-size: 0.8125rem;
  color: var(--color-lamp-300);
  background: var(--color-base-950);
}
</style>
