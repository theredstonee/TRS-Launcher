<script setup lang="ts">
// Ein Feld für alle Codes (§44.2): Creator → Partner → Umhang/Kosmetik über `POST /v1/me/codes/redeem`.
const props = withDefaults(defineProps<{ initial?: string }>(), { initial: '' })
const { p } = usePartnerText()
const { fill, date } = useLang()
const { account, loaded, load, api, loginUrl } = useAccount()

const code = ref(props.initial)
const busy = ref(false)
const result = ref<{ ok: boolean, lines: string[] } | null>(null)
watch(() => props.initial, (v) => {
  if (v) code.value = v
})
onMounted(() => void load())

type Answer =
  | { kind: 'creator', creator: { name: string }, supportChanged: boolean, switchAvailableAt: string | null }
  | { kind: 'partner', partner: { name: string }, supportChanged: boolean, switchAvailableAt: string | null, oneTime: boolean, granted: { capes: { name: string }[], cosmetics: { name: string }[] } }
  | { kind: 'cape', cape: { name: string } | null, cosmetic: { name: string } | null, alreadyOwned: boolean }

async function redeem() {
  const c = code.value.trim()
  if (!c || busy.value) return
  busy.value = true
  result.value = null
  const t = p.value.redeem
  try {
    const r = await api<Answer>('/v1/me/codes/redeem', { method: 'POST', body: { code: c.slice(0, 64) } })
    const lines: string[] = []
    if (r.kind === 'partner') {
      if (r.supportChanged) lines.push(fill(t.partnerStarted, { name: r.partner.name }))
      else if (r.switchAvailableAt) lines.push(fill(t.partnerKept, { name: r.partner.name, date: date(r.switchAvailableAt) }))
      else lines.push(fill(t.partnerSame, { name: r.partner.name }))
      const items = [...r.granted.capes, ...r.granted.cosmetics].map((x) => x.name)
      if (items.length) lines.push(fill(t.granted, { items: items.join(', ') }))
      if (r.oneTime) lines.push(t.oneTime)
    } else if (r.kind === 'creator') {
      lines.push(r.supportChanged || !r.switchAvailableAt ? fill(t.creator, { name: r.creator.name }) : fill(t.creatorKept, { name: r.creator.name, date: date(r.switchAvailableAt) }))
    } else {
      const name = r.cape?.name ?? r.cosmetic?.name ?? ''
      lines.push(fill(r.alreadyOwned ? t.owned : t.cape, { name }))
    }
    result.value = { ok: true, lines }
    code.value = ''
  } catch (e) {
    const c2 = apiCode(e)
    result.value = { ok: false, lines: [t.errors[c2] ?? fill(t.failed, { error: apiMessage(e) })] }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="card p-5 sm:p-6" aria-labelledby="redeem-title">
    <h2 id="redeem-title" class="heading text-2xl text-base-50">{{ p.redeem.title }}</h2>
    <p class="mt-1 text-sm text-base-400">{{ p.redeem.text }}</p>
    <div v-if="!loaded" class="skeleton mt-4 h-11 rounded-lg" />
    <form v-else-if="account" class="mt-4 flex flex-wrap gap-2" @submit.prevent="redeem">
      <input v-model="code" class="field code-input max-w-xs flex-1" maxlength="64" autocomplete="off" spellcheck="false" :placeholder="p.redeem.placeholder" :aria-label="p.redeem.title" />
      <button type="submit" class="btn btn-primary" :disabled="busy || !code.trim()"><SiteIcon name="ticket" class="size-4" />{{ p.redeem.button }}</button>
    </form>
    <div v-else class="mt-4 flex flex-wrap items-center gap-3">
      <p class="text-sm text-base-300">{{ p.redeem.signIn }}</p>
      <a :href="loginUrl()" class="btn btn-ghost"><SiteIcon name="user" class="size-4" />{{ p.redeem.signInButton }}</a>
    </div>
    <div v-if="result" class="mt-3 text-sm" :class="result.ok ? 'text-ok' : 'text-redstone-300'" :role="result.ok ? 'status' : 'alert'">
      <p v-for="(l, i) in result.lines" :key="i">{{ l }}</p>
    </div>
  </section>
</template>

<style scoped>
.code-input {
  font-family: var(--font-mono);
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
</style>
