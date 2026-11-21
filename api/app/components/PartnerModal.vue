<script setup lang="ts">
// Partner-Fenster (§44.3): links Logo, Name, Kategorie, Unterstützer und Code; rechts Vorstellung (reiner Text), Links,
// Partner-Server und Partner-Kosmetik. Escape schließt, der Fokus bleibt im Fenster.
// `embedded`: als Vorschau im Dashboard eingebettet (ohne Hintergrund, Spalten nach Containerbreite).
const props = defineProps<{ partner: PartnerCard, embedded?: boolean, textLang?: Lang }>()
const emit = defineEmits<{ close: [], redeem: [code: string] }>()

const { lang, fill } = useLang()
const { p } = usePartnerText()
const t = computed(() => p.value.pub)

const dialog = shallowRef<HTMLElement | null>(null)
const closeButton = shallowRef<HTMLButtonElement | null>(null)
let before: HTMLElement | null = null

const paragraphs = computed(() => partnerText(props.partner.text, props.textLang ?? lang.value).split(/\n{2,}/).map((s) => s.trim()).filter(Boolean))
const supporters = computed(() => (props.partner.supporters === 1 ? t.value.oneSupporter : fill(t.value.supporters, { n: compactCount(props.partner.supporters, lang.value) })))
const titleId = computed(() => `partner-title-${props.partner.slug}${props.embedded ? '-preview' : ''}`)
const copied = ref(false)
async function copyCode() {
  if (!props.partner.code) return
  try {
    await navigator.clipboard.writeText(props.partner.code)
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // Zwischenablage gesperrt – der Code steht ja da.
  }
}

const FOCUSABLE = 'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])'
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
    return
  }
  if (e.key !== 'Tab' || !dialog.value) return
  const items = [...dialog.value.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null)
  if (!items.length) return
  const first = items[0]!
  const last = items[items.length - 1]!
  if (e.shiftKey && (document.activeElement === first || !dialog.value.contains(document.activeElement))) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}
onMounted(() => {
  if (props.embedded) return
  before = document.activeElement as HTMLElement | null
  document.addEventListener('keydown', onKey)
  document.documentElement.classList.add('modal-open')
  void nextTick(() => closeButton.value?.focus())
})
onBeforeUnmount(() => {
  if (props.embedded) return
  document.removeEventListener('keydown', onKey)
  document.documentElement.classList.remove('modal-open')
  before?.focus?.()
})
watch(() => props.partner.slug, () => {
  dialog.value?.querySelector('.modal-body')?.scrollTo({ top: 0 })
})
</script>

<template>
  <div :class="embedded ? 'partner-embed' : 'partner-backdrop'" @click.self="!embedded && emit('close')">
    <div ref="dialog" class="partner-modal" :class="{ embedded }" :role="embedded ? undefined : 'dialog'" :aria-modal="embedded ? undefined : 'true'" :aria-labelledby="titleId">
      <button v-if="!embedded" ref="closeButton" type="button" class="modal-close" :aria-label="t.close" @click="emit('close')">
        <SiteIcon name="close" class="size-5" />
      </button>
      <div class="modal-body">
        <aside class="modal-side">
          <PartnerLogo :logo="partner.logo" :name="partner.name" :size="112" />
          <h2 :id="titleId" class="mt-4 text-2xl font-bold break-words text-base-50">{{ partner.name }}</h2>
          <p class="cat-label mt-1">{{ p.category[partner.category] ?? partner.category }}</p>
          <p class="mt-3 text-sm font-semibold text-base-200">
            <SiteIcon name="users" class="mr-1 inline size-4 align-[-3px] text-base-400" />{{ supporters }}
          </p>
          <div v-if="partner.code" class="code-box mt-6">
            <div class="flex items-center justify-between gap-2">
              <p class="text-[11px] font-semibold tracking-[0.16em] text-base-400 uppercase">{{ t.code }}</p>
              <button type="button" class="btn-icon size-8" :aria-label="copied ? t.copied : t.copy" @click="copyCode">
                <SiteIcon :name="copied ? 'check' : 'copy'" class="size-4" />
              </button>
            </div>
            <PartnerCode :code="partner.code" class="mt-2" />
            <p class="mt-2 text-xs text-base-400">{{ t.codeHint }}</p>
            <button type="button" class="btn btn-primary mt-3 w-full justify-center" @click="emit('redeem', partner.code)">
              <SiteIcon name="ticket" class="size-4" />{{ p.redeem.button }}
            </button>
          </div>
        </aside>

        <div class="modal-main">
          <div v-if="paragraphs.length" class="description">
            <p v-for="(para, i) in paragraphs" :key="i" class="whitespace-pre-line">{{ para }}</p>
          </div>
          <p v-else class="text-base-400 italic">{{ fill(t.noText, { name: partner.name }) }}</p>

          <section v-if="partner.links.length || partner.wikiUrl" class="mt-7" :aria-label="t.linksTitle">
            <h3 class="sub-title">{{ t.linksTitle }}</h3>
            <ul class="mt-3 flex flex-wrap gap-2">
              <li v-for="l in partner.links" :key="l.url">
                <a :href="l.url" target="_blank" rel="noopener noreferrer nofollow" class="link-chip">
                  <SiteIcon name="globe" class="size-4" />{{ l.label }}<SiteIcon name="external" class="size-3 opacity-60" />
                </a>
              </li>
              <li v-if="partner.wikiUrl">
                <a :href="partner.wikiUrl" target="_blank" rel="noopener" class="link-chip">
                  <SiteIcon name="docs" class="size-4" />{{ t.wiki }}<SiteIcon name="external" class="size-3 opacity-60" />
                </a>
              </li>
            </ul>
          </section>

          <section v-if="partner.server" class="server-note mt-7">
            <SiteIcon name="server" class="size-5 shrink-0 text-redstone-300" />
            <div>
              <p class="text-sm font-semibold text-base-50">{{ t.serverTitle }} · {{ partner.server.name }}</p>
              <p class="mt-0.5 text-sm text-base-300">{{ fill(t.serverText, { address: partner.server.address }) }}</p>
            </div>
          </section>

          <section v-if="partner.cosmetics.length" class="mt-7" :aria-label="t.cosmeticsTitle">
            <h3 class="sub-title">{{ t.cosmeticsTitle }}</h3>
            <p class="mt-1 text-xs text-base-400">{{ t.cosmeticsText }}</p>
            <ul class="mt-3 grid gap-2 sm:grid-cols-2">
              <li v-for="c in partner.cosmetics" :key="`${c.kind}:${c.id}`" class="item-card">
                <ClientOnly>
                  <PartnerCapeThumb v-if="c.kind === 'cape'" :id="c.id" :width="40" />
                  <span v-else class="item-icon"><SiteIcon name="crown" class="size-6" /></span>
                  <template #fallback><span class="item-icon" /></template>
                </ClientOnly>
                <span class="min-w-0">
                  <span class="block truncate font-semibold text-base-50">{{ c.name }}</span>
                  <span class="block text-xs text-base-400">{{ c.kind === 'cape' ? t.cape : t.cosmetic }}</span>
                </span>
              </li>
            </ul>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.partner-backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: color-mix(in srgb, black 68%, transparent);
  backdrop-filter: blur(3px);
  animation: fade 0.18s ease-out;
}
.partner-modal {
  position: relative;
  width: min(56rem, 100%);
  max-height: calc(100dvh - 2rem);
  display: flex;
  border-radius: 1.1rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  box-shadow: 0 30px 80px -20px rgb(0 0 0 / 0.75);
  animation: rise 0.22s ease-out;
}
.modal-body {
  display: grid;
  width: 100%;
  overflow-y: auto;
  overscroll-behavior: contain;
}
@media (min-width: 768px) {
  .modal-body {
    grid-template-columns: 16rem 1fr;
  }
}
.modal-side {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 2.25rem 1.5rem 1.75rem;
  text-align: center;
  border-bottom: 1px solid var(--color-base-800);
  background: radial-gradient(90% 60% at 50% 0%, color-mix(in srgb, var(--color-redstone-500) 10%, transparent), transparent 75%);
}
@media (min-width: 768px) {
  .modal-side {
    border-bottom: 0;
    border-right: 1px solid var(--color-base-800);
  }
}
.modal-main {
  padding: 2.25rem 3.5rem 2rem 1.75rem;
  min-width: 0;
}
.modal-close {
  position: absolute;
  top: 0.75rem;
  right: 0.75rem;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 999px;
  color: var(--color-base-400);
  background: color-mix(in srgb, var(--color-base-900) 80%, transparent);
}
.modal-close:hover,
.modal-close:focus-visible {
  color: var(--color-base-50);
  background: var(--color-base-800);
}
.cat-label {
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--color-redstone-400);
}
.code-box {
  width: 100%;
  min-width: 0;
  border-radius: 0.8rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
  padding: 0.9rem;
}
.description {
  display: grid;
  gap: 0.9rem;
  line-height: 1.7;
  color: var(--color-base-200);
  overflow-wrap: anywhere;
}
.sub-title {
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--color-base-400);
}
.link-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-850);
  padding: 0.4rem 0.85rem;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--color-base-50);
  transition: border-color 0.15s, background-color 0.15s;
}
.link-chip:hover,
.link-chip:focus-visible {
  border-color: var(--color-redstone-500);
  background: var(--color-base-800);
}
.server-note {
  display: flex;
  gap: 0.75rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-base-800);
  border-left: 3px solid var(--color-redstone-500);
  background: var(--color-base-950);
  padding: 0.8rem 1rem;
}
.item-card {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-850);
  padding: 0.6rem 0.75rem;
}
.item-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 64px;
  border-radius: 0.3rem;
  background: var(--color-base-800);
  color: var(--color-lamp-300);
}
.partner-embed {
  container-type: inline-size;
}
.partner-modal.embedded {
  width: 100%;
  max-height: none;
  box-shadow: none;
  animation: none;
}
.embedded .modal-body {
  grid-template-columns: minmax(0, 1fr);
  overflow: visible;
}
.embedded .modal-side {
  border-right: 0;
  border-bottom: 1px solid var(--color-base-800);
}
.embedded .modal-main {
  padding: 1.75rem 1.5rem;
}
@container (min-width: 44rem) {
  .embedded .modal-body {
    grid-template-columns: 16rem minmax(0, 1fr);
  }
  .embedded .modal-side {
    border-bottom: 0;
    border-right: 1px solid var(--color-base-800);
  }
}
@keyframes fade {
  from {
    opacity: 0;
  }
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.985);
  }
}
@media (prefers-reduced-motion: reduce) {
  .partner-backdrop,
  .partner-modal {
    animation: none;
  }
}
</style>
