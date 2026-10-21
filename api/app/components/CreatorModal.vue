<script setup lang="ts">
// Creator-Fenster (§35.11): links Kopf, Name, Stufe, Fan-Symbol und Fans; rechts Vorstellung (reiner Text: Absätze und
// einfache Listen), Links, Fan-Umhang und Creator-Packs. Escape schließt, der Fokus bleibt im Fenster und springt danach
// zurück. Text wird nur als Text gerendert – nie als HTML.
const props = defineProps<{ creator: SiteCreator }>()
const emit = defineEmits<{ close: [] }>()

const { m, fill, date, lang } = useLang()
const lp = useLocalePath()
const t = computed(() => m.value.creators)

const dialog = shallowRef<HTMLElement | null>(null)
const closeButton = shallowRef<HTMLButtonElement | null>(null)
let before: HTMLElement | null = null

const blocks = computed(() => descriptionBlocks(props.creator.description))
const fans = computed(() => (props.creator.fanCount === 1 ? t.value.oneFan : fill(t.value.fans, { n: compactCount(props.creator.fanCount, lang.value) })))
const titleId = computed(() => `creator-title-${props.creator.uuid}`)

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
  before = document.activeElement as HTMLElement | null
  document.addEventListener('keydown', onKey)
  document.documentElement.classList.add('modal-open')
  void nextTick(() => closeButton.value?.focus())
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
  document.documentElement.classList.remove('modal-open')
  before?.focus?.()
})
// Anderer Creator im selben Fenster (Vor/Zurück im Verlauf): oben anfangen.
watch(() => props.creator.uuid, () => {
  dialog.value?.querySelector('.modal-body')?.scrollTo({ top: 0 })
  void nextTick(() => closeButton.value?.focus())
})
</script>

<template>
  <div class="creator-backdrop" @click.self="emit('close')">
    <div ref="dialog" class="creator-modal" role="dialog" aria-modal="true" :aria-labelledby="titleId">
      <button ref="closeButton" type="button" class="modal-close" :aria-label="t.close" @click="emit('close')">
        <SiteIcon name="close" class="size-5" />
      </button>
      <div class="modal-body">
        <aside class="modal-side">
          <CreatorAvatar :uuid="creator.uuid" :name="creator.name" :skin="creator.skin" :tier="creator.tier" :size="112" />
          <h2 :id="titleId" class="mt-4 flex items-center justify-center gap-2 text-2xl font-bold break-all text-base-50">
            <CreatorSymbol v-if="creator.symbol" :url="creator.symbol.url" :frames="creator.symbol.frames" :frame-ms="creator.symbol.frameMs" :px="20" :label="t.fanSymbol" />
            {{ creator.name }}
          </h2>
          <p class="tier-label mt-1">{{ t.tier[creator.tier] }}</p>
          <p class="mt-3 text-sm font-semibold text-base-200">
            <SiteIcon name="users" class="mr-1 inline size-4 align-[-3px] text-base-400" />{{ fans }}
          </p>
          <p v-if="creator.milestone" class="milestone mt-2" :data-m="creator.milestone">{{ t.milestones[creator.milestone] }}</p>
          <p class="mt-2 text-xs text-base-400">{{ fill(t.since, { date: date(creator.since) }) }}</p>

          <div v-if="creator.fanCape" class="cape-box mt-6">
            <p class="text-[11px] font-semibold tracking-[0.16em] text-base-400 uppercase">{{ t.fanCape }}</p>
            <ClientOnly>
              <CapeThumb :texture="creator.fanCape.url" :scale="creator.fanCape.scale" :frames="creator.fanCape.frames" :frame-time-ms="creator.fanCape.frameTimeMs" :width="64" class="mx-auto mt-3" />
              <template #fallback><span class="mx-auto mt-3 block h-[102px] w-16 rounded-sm bg-base-800" /></template>
            </ClientOnly>
            <p class="mt-2 text-sm font-medium text-base-100">{{ creator.fanCape.name }}</p>
            <p class="mt-1 text-xs text-base-400">{{ fill(t.fanCapeHint, { name: creator.name }) }}</p>
          </div>
        </aside>

        <div class="modal-main">
          <div v-if="blocks.length" class="description">
            <template v-for="(b, i) in blocks" :key="i">
              <p v-if="b.kind === 'p'" class="whitespace-pre-line">{{ b.text }}</p>
              <ul v-else>
                <li v-for="(item, j) in b.items" :key="j">{{ item }}</li>
              </ul>
            </template>
          </div>
          <p v-else class="text-base-400 italic">{{ fill(t.noDescription, { name: creator.name }) }}</p>

          <section v-if="creator.links.length" class="mt-7" :aria-label="t.linksTitle">
            <h3 class="sub-title">{{ t.linksTitle }}</h3>
            <ul class="mt-3 flex flex-wrap gap-2">
              <li v-for="l in creator.links" :key="l.url">
                <a :href="l.url" target="_blank" rel="noopener noreferrer nofollow" class="link-chip" :data-type="l.type">
                  <SiteIcon :name="LINK_ICONS[l.type]" class="size-4" />{{ t.linkNames[l.type] }}
                  <SiteIcon name="external" class="size-3 opacity-60" />
                </a>
              </li>
            </ul>
          </section>

          <section v-if="creator.packs.length" class="mt-7" :aria-label="t.packsTitle">
            <h3 class="sub-title">{{ t.packsTitle }}</h3>
            <ul class="mt-3 grid gap-2 sm:grid-cols-2">
              <li v-for="p in creator.packs" :key="p.code">
                <NuxtLink :to="lp(`/p/${p.code}`)" class="pack-card">
                  <span class="block truncate font-semibold text-base-50">{{ p.name }}</span>
                  <span class="mt-0.5 block text-xs text-base-400">{{ p.mcVersion }} · {{ p.loader }} · {{ fill(t.installs, { n: compactCount(p.installs, lang) }) }}</span>
                  <span v-if="p.summary" class="mt-1 line-clamp-2 block text-xs text-base-300">{{ p.summary }}</span>
                </NuxtLink>
              </li>
            </ul>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.creator-backdrop {
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
.creator-modal {
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
  transition: color 0.15s, background-color 0.15s;
}
.modal-close:hover,
.modal-close:focus-visible {
  color: var(--color-base-50);
  background: var(--color-base-800);
}
.tier-label {
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--color-redstone-400);
}
.milestone {
  display: inline-block;
  border-radius: 999px;
  padding: 0.1rem 0.6rem;
  font-size: 0.7rem;
  font-weight: 600;
  background: var(--color-base-800);
  color: var(--color-base-200);
}
.milestone[data-m='gold'],
.milestone[data-m='diamond'] {
  color: var(--color-lamp-300);
}
.cape-box {
  width: 100%;
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
.description ul {
  display: grid;
  gap: 0.3rem;
  padding-left: 1.2rem;
  list-style: none;
}
.description li {
  position: relative;
}
.description li::before {
  content: '';
  position: absolute;
  left: -0.95rem;
  top: 0.7em;
  width: 0.4rem;
  height: 0.4rem;
  background: var(--color-redstone-500);
  box-shadow: 0 0 6px var(--color-redstone-500);
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
.pack-card {
  display: block;
  height: 100%;
  border-radius: 0.7rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-850);
  padding: 0.7rem 0.85rem;
  transition: border-color 0.15s;
}
.pack-card:hover,
.pack-card:focus-visible {
  border-color: var(--color-base-600);
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
  .creator-backdrop,
  .creator-modal {
    animation: none;
  }
}
</style>
