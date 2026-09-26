<script setup lang="ts">
import type { TrsCape, TrsCode } from '~/utils/trs'

// Einlösecodes: Liste für das ganze Team, Erstellen und Widerrufen nur für
// Admins. Die Klartext-Codes gibt es nur direkt nach dem Erstellen.
const toasts = useToasts()
const team = useTeam()

const catalog = ref<TrsCape[]>([])
const grantable = computed(() => catalog.value.filter((c) => c.kind === 'builtin' && c.unlock !== 'free'))
const codes = ref<TrsCode[] | null>(null)
const created = ref<TrsCode[]>([])
const form = reactive({ capeId: '', maxUses: 1, count: 1, expires: '', note: '' })
const formError = ref<string | null>(null)
const busy = ref(false)
const revoking = ref<TrsCode | null>(null)
const filter = ref<'active' | 'all'>('active')

const shown = computed(() => (codes.value ?? []).filter((c) => filter.value === 'all' || !c.revokedAt))

async function load() {
  try {
    codes.value = await backend.trs.adminCodes()
  } catch (e) {
    codes.value = []
    toasts.error(e)
  }
}
onMounted(async () => {
  void load()
  catalog.value = await backend.trs.capes().catch(() => [])
})

function itemName(c: TrsCode): string {
  if (c.capeId) return catalog.value.find((x) => x.id === c.capeId)?.name ?? c.capeId
  return c.cosmeticId ? t('team.codes.cosmetic', { id: c.cosmeticId }) : '–'
}

async function create() {
  const expiresAt = form.expires ? new Date(`${form.expires}T23:59:59`).toISOString() : null
  const parsed = trsNewCodesSchema.safeParse({
    capeId: form.capeId,
    maxUses: Number(form.maxUses),
    count: Number(form.count),
    expiresAt,
    note: form.note.trim() || null,
  })
  if (!parsed.success) {
    formError.value = form.capeId ? firstIssue(parsed.error) : t('admin.codes.pickCape')
    return
  }
  formError.value = null
  busy.value = true
  try {
    const { capeId, maxUses, count, expiresAt: at, note } = parsed.data
    created.value = await backend.trs.adminCreateCodes({ capeId, maxUses, count, ...(at ? { expiresAt: at } : {}), ...(note ? { note } : {}) })
    toasts.ok(t('admin.toasts.codesCreated', created.value.length))
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

async function copy(text: string, what: 'code' | 'all' = 'code') {
  try {
    await navigator.clipboard.writeText(text)
    toasts.ok(t(`admin.toasts.copied.${what}`))
  } catch {
    toasts.error(t('admin.toasts.copyFailed'))
  }
}

async function revoke() {
  const c = revoking.value
  if (!c) return
  busy.value = true
  try {
    await backend.trs.adminRevokeCode(c.id)
    toasts.ok(t('admin.toasts.codeRevoked'))
    revoking.value = null
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="space-y-4" :aria-label="t('team.nav.codes')" data-testid="admin-codes">
    <form v-if="team.isAdmin.value" class="card grid grid-cols-1 gap-3 p-4 md:grid-cols-[2fr_1fr_1fr_1.4fr]" @submit.prevent="create">
      <label class="block">
        <span class="label">{{ t('admin.codes.cape') }}</span>
        <select v-model="form.capeId" class="field">
          <option value="" disabled>{{ t('admin.codes.chooseCape') }}</option>
          <option v-for="c in grantable" :key="c.id" :value="c.id">{{ c.name }} ({{ trsUnlockLabel(c) }})</option>
        </select>
      </label>
      <label class="block">
        <span class="label">{{ t('admin.codes.usesPerCode') }}</span>
        <input v-model.number="form.maxUses" type="number" min="1" max="100000" class="field" />
      </label>
      <label class="block">
        <span class="label">{{ t('admin.codes.count') }}</span>
        <input v-model.number="form.count" type="number" min="1" max="100" class="field" />
      </label>
      <label class="block">
        <span class="label">{{ t('admin.codes.validUntil') }}</span>
        <input v-model="form.expires" type="date" class="field" />
      </label>
      <label class="block md:col-span-3">
        <span class="label">{{ t('admin.codes.note') }}</span>
        <input v-model="form.note" class="field" maxlength="200" :placeholder="t('admin.codes.notePlaceholder')" />
      </label>
      <div class="flex items-end">
        <button class="btn btn-primary w-full" :disabled="busy">{{ busy ? t('admin.codes.creating') : t('admin.codes.create') }}</button>
      </div>
      <p v-if="formError" role="alert" class="text-xs text-redstone-300 md:col-span-4">{{ formError }}</p>
    </form>
    <p v-else class="card px-4 py-3 text-xs text-base-400">{{ t('team.common.readOnly') }}</p>

    <div v-if="created.length" class="card border-lamp-400/40 p-4" data-testid="admin-created-codes">
      <div class="mb-2 flex items-center gap-2">
        <p class="flex-1 text-sm font-semibold text-lamp-300">{{ t('admin.codes.newCodes') }}</p>
        <button class="btn btn-ghost px-3 py-1 text-xs" @click="copy(created.map((c) => c.code).join('\n'), 'all')">{{ t('admin.codes.copyAll') }}</button>
        <button class="btn btn-ghost px-3 py-1 text-xs" @click="created = []">{{ t('admin.codes.hide') }}</button>
      </div>
      <ul class="grid gap-1.5 sm:grid-cols-2">
        <li v-for="c in created" :key="c.id" class="flex items-center gap-2 rounded-md bg-base-850 px-3 py-1.5">
          <code class="flex-1 font-mono text-sm tracking-wider text-base-50 select-all">{{ c.code }}</code>
          <button class="btn btn-ghost px-2 py-0.5 text-[11px]" @click="copy(c.code ?? '')">{{ t('common.actions.copy') }}</button>
        </li>
      </ul>
    </div>

    <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs self-start" style="width: fit-content">
      <button class="seg rounded-md" :class="{ 'seg-on': filter === 'active' }" @click="filter = 'active'">{{ t('team.codes.active') }}</button>
      <button class="seg rounded-md" :class="{ 'seg-on': filter === 'all' }" @click="filter = 'all'">{{ t('team.codes.all') }}</button>
    </div>
    <div v-if="!codes" class="skeleton h-40" />
    <RedstoneEmpty v-else-if="!shown.length" :title="t('admin.codes.empty')" compact :seed="0x19" />
    <div v-else class="card overflow-x-auto">
      <table class="w-full text-left text-xs">
        <thead class="text-base-400">
          <tr class="border-b border-base-800">
            <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.code') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.cape') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.redeemed') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.validUntil') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('admin.codes.table.note') }}</th>
            <th class="px-3 py-2" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in shown" :key="c.id" class="border-b border-base-800/60 last:border-0" :class="{ 'opacity-50': c.revokedAt }">
            <td class="px-3 py-2 font-mono text-base-200">…{{ c.hint }}</td>
            <td class="px-3 py-2">{{ itemName(c) }}</td>
            <td class="px-3 py-2 tabular-nums">{{ c.uses }} / {{ c.maxUses }}</td>
            <td class="px-3 py-2">{{ c.expiresAt ? trsDate(c.expiresAt) : t('admin.codes.unlimited') }}</td>
            <td class="max-w-48 truncate px-3 py-2 text-base-400" :title="c.note ?? ''">{{ c.note ?? '–' }}</td>
            <td class="px-3 py-2 text-right">
              <span v-if="c.revokedAt" class="text-base-600">{{ t('admin.codes.revoked') }}</span>
              <button v-else-if="team.isAdmin.value" class="btn btn-ghost px-2 py-0.5 text-[11px] hover:text-redstone-300" @click="revoking = c">{{ t('admin.codes.revoke') }}</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <BaseDialog v-if="revoking" :title="t('admin.dialogs.revokeTitle')" @close="revoking = null">
      <i18n-t keypath="admin.dialogs.revokeText" tag="p" scope="global" class="text-sm text-base-200">
        <template #code><span class="font-mono">…{{ revoking.hint }}</span></template>
        <template #cape>{{ itemName(revoking) }}</template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="revoking = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy" @click="revoke">{{ t('admin.codes.revoke') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>
