<script setup lang="ts">
import { LANDING_IDS, landingPath, landingTexts } from '#shared/landing'

const { m, lang } = useLang()
const { t } = useTeamText()
const { c } = useCircuitText()
const { it } = useIssueText()
const lp = useLocalePath()
const route = useRoute()
const menuOpen = ref(false)
const menuButton = shallowRef<HTMLButtonElement | null>(null)
const menuPanel = shallowRef<HTMLElement | null>(null)

// Alle Seiten stecken im Menü (immer, auf jeder Breite) – oben bleiben nur Logo, Sprache, Konto, GitHub, Download.
const links = computed(() => [
  { to: lp('/features'), label: m.value.nav.features, icon: 'bolt' },
  { to: lp('/download'), label: m.value.nav.download, icon: 'download' },
  { to: lp('/blog'), label: m.value.nav.blog, icon: 'book' },
  { to: lp('/capes'), label: m.value.nav.capes, icon: 'cape' },
  { to: lp('/circuits'), label: c.value.nav, icon: 'blocks' },
  { to: lp('/issues'), label: it.value.nav.issues, icon: 'bug' },
  { to: lp('/roadmap'), label: it.value.nav.roadmap, icon: 'roadmap' },
  { to: lp('/faq'), label: m.value.nav.faq, icon: 'note' },
  { to: lp('/team'), label: m.value.nav.team, icon: 'users' },
  { to: lp('/applications'), label: t.value.account.myApplications, icon: 'inbox' },
])

// Themen-Seiten (shared/landing.ts): im Menü unter den Seiten, in der Fußzeile als eigene Spalte.
const landing = computed(() => landingTexts[lang.value])
const topics = computed(() => LANDING_IDS.map((id) => ({ to: lp(landingPath(id)), label: landing.value.pages[id].name })))

/** Aktuelle Seite (auch Unterseiten wie /blog/0.9.0) – ohne Sprach-Parameter vergleichen. */
function isCurrent(to: string): boolean {
  const target = to.split('?')[0]!.replace(/\/+$/, '') || '/'
  const here = route.path.replace(/\/+$/, '') || '/'
  return target === '/' ? here === '/' : here === target || here.startsWith(`${target}/`)
}

function closeMenu(focusButton = false) {
  if (!menuOpen.value) return
  menuOpen.value = false
  if (focusButton) void nextTick(() => menuButton.value?.focus())
}
function toggleMenu() {
  menuOpen.value = !menuOpen.value
  if (menuOpen.value) void nextTick(() => menuPanel.value?.querySelector<HTMLElement>('a')?.focus())
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && menuOpen.value) {
    e.preventDefault()
    closeMenu(true)
  }
}
function onPointer(e: PointerEvent) {
  if (!menuOpen.value) return
  const target = e.target as Node
  if (menuPanel.value?.contains(target) || menuButton.value?.contains(target)) return
  closeMenu()
}
onMounted(() => {
  document.addEventListener('keydown', onKey)
  document.addEventListener('pointerdown', onPointer)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
  document.removeEventListener('pointerdown', onPointer)
})
watch(() => route.fullPath, () => closeMenu())
const year = new Date().getFullYear()
</script>

<template>
  <div class="flex min-h-screen flex-col bg-base-950 text-base-200">
    <a href="#main" class="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 btn btn-primary">Skip to content</a>

    <header class="site-header sticky top-0 z-30">
      <div class="relative mx-auto flex h-16 max-w-6xl items-center gap-2 px-4 sm:gap-3 sm:px-6">
        <button
          ref="menuButton"
          type="button"
          class="menu-button"
          :class="{ open: menuOpen }"
          :aria-label="m.nav.menu"
          :aria-expanded="menuOpen"
          aria-controls="site-menu"
          @click="toggleMenu"
        >
          <SiteIcon :name="menuOpen ? 'close' : 'menu'" class="size-5" />
          <span class="hidden text-sm font-semibold md:inline">{{ m.nav.menu }}</span>
        </button>

        <NuxtLink :to="lp('/')" class="flex min-w-0 items-center gap-2.5 text-base-50" aria-label="TRS Launcher">
          <img src="/icon.png" alt="" width="32" height="32" class="size-8 shrink-0 [image-rendering:pixelated]" />
          <span class="display hidden text-xl leading-none min-[400px]:inline">TRS Launcher</span>
        </NuxtLink>

        <div class="ml-auto flex items-center gap-2">
          <LangSwitch />
          <AccountMenu />
          <a :href="REPO_URL" class="btn-icon hidden sm:inline-flex" aria-label="GitHub" rel="noopener" target="_blank">
            <SiteIcon name="github" class="size-4.5" />
          </a>
          <NuxtLink :to="lp('/download')" class="btn btn-primary hidden sm:inline-flex">
            <SiteIcon name="download" class="size-4" />
            {{ m.nav.download }}
          </NuxtLink>
        </div>

        <Transition name="menu">
          <nav v-if="menuOpen" id="site-menu" ref="menuPanel" class="menu-panel" :aria-label="m.nav.menu">
            <ul class="grid gap-1 sm:grid-cols-2">
              <li v-for="l in links" :key="l.to">
                <NuxtLink :to="l.to" class="menu-link" :aria-current="isCurrent(l.to) ? 'page' : undefined" @click="closeMenu()">
                  <SiteIcon :name="l.icon" class="size-4.5 shrink-0" />
                  <span>{{ l.label }}</span>
                </NuxtLink>
              </li>
            </ul>
            <p class="mt-3 border-t border-base-800 px-3 pt-3 text-[11px] font-semibold tracking-[0.18em] text-base-400 uppercase">{{ landing.common.topics }}</p>
            <ul class="mt-1 grid gap-1 sm:grid-cols-2">
              <li v-for="l in topics" :key="l.to">
                <NuxtLink :to="l.to" class="menu-link menu-link-sm" :aria-current="isCurrent(l.to) ? 'page' : undefined" @click="closeMenu()">
                  <span>{{ l.label }}</span>
                </NuxtLink>
              </li>
            </ul>
            <div class="mt-3 flex gap-2 border-t border-base-800 pt-3 sm:hidden">
              <NuxtLink :to="lp('/download')" class="btn btn-primary flex-1" @click="closeMenu()"><SiteIcon name="download" class="size-4" />{{ m.nav.download }}</NuxtLink>
              <a :href="REPO_URL" class="btn-icon" aria-label="GitHub" rel="noopener" target="_blank"><SiteIcon name="github" class="size-4.5" /></a>
            </div>
          </nav>
        </Transition>
      </div>
    </header>

    <main id="main" class="flex-1">
      <slot />
    </main>

    <footer class="site-footer mt-24">
      <div class="dust-line" aria-hidden="true" />
      <div class="mx-auto grid max-w-6xl gap-10 px-4 py-12 sm:px-6 sm:grid-cols-2 md:grid-cols-[1.4fr_1fr_1fr_1fr]">
        <div>
          <div class="flex items-center gap-2.5 text-base-50">
            <img src="/icon.png" alt="" width="28" height="28" class="size-7 [image-rendering:pixelated]" />
            <span class="display text-lg">TRS Launcher</span>
          </div>
          <p class="mt-3 max-w-sm text-sm text-base-400">{{ m.footer.tagline }}</p>
          <p class="mt-3 max-w-sm text-xs text-base-600">{{ m.footer.notAffiliated }}</p>
        </div>
        <nav class="flex flex-col gap-2 text-sm" aria-label="Site">
          <NuxtLink :to="lp('/features')" class="footer-link">{{ m.nav.features }}</NuxtLink>
          <NuxtLink :to="lp('/download')" class="footer-link">{{ m.nav.download }}</NuxtLink>
          <NuxtLink :to="lp('/blog')" class="footer-link">{{ m.nav.blog }}</NuxtLink>
          <NuxtLink :to="lp('/capes')" class="footer-link">{{ m.nav.capes }}</NuxtLink>
          <NuxtLink :to="lp('/circuits')" class="footer-link">{{ c.nav }}</NuxtLink>
          <NuxtLink :to="lp('/issues')" class="footer-link">{{ it.nav.issues }}</NuxtLink>
          <NuxtLink :to="lp('/roadmap')" class="footer-link">{{ it.nav.roadmap }}</NuxtLink>
          <NuxtLink :to="lp('/faq')" class="footer-link">{{ m.nav.faq }}</NuxtLink>
          <NuxtLink :to="lp('/team')" class="footer-link">{{ m.nav.team }}</NuxtLink>
        </nav>
        <nav class="flex flex-col gap-2 text-sm" :aria-label="landing.common.topics">
          <p class="text-xs font-semibold tracking-[0.18em] text-base-400 uppercase">{{ landing.common.topics }}</p>
          <NuxtLink v-for="l in topics" :key="l.to" :to="l.to" class="footer-link">{{ l.label }}</NuxtLink>
        </nav>
        <nav class="flex flex-col gap-2 text-sm" aria-label="Links">
          <a :href="REPO_URL" class="footer-link" rel="noopener" target="_blank">{{ m.footer.github }}</a>
          <a :href="DISCORD_URL" class="footer-link" rel="noopener" target="_blank">{{ m.footer.discord }}</a>
          <a :href="WIKI_URL" class="footer-link" rel="noopener" target="_blank">{{ m.footer.wiki }}</a>
          <a :href="IMPRINT_URL" class="footer-link" rel="noopener">{{ m.footer.imprint }}</a>
          <NuxtLink :to="lp('/privacy')" class="footer-link">{{ m.footer.privacy }}</NuxtLink>
        </nav>
      </div>
      <div class="border-t border-base-800">
        <div class="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-2 px-4 py-4 text-xs text-base-600 sm:px-6">
          <span>© {{ year }} Theredstonee · GPL-3.0</span>
          <NuxtLink to="/admin" rel="nofollow" class="hover:text-base-400">{{ m.nav.admin }}</NuxtLink>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.site-header {
  background: color-mix(in srgb, var(--color-base-950) 82%, transparent);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid color-mix(in srgb, var(--color-base-800) 70%, transparent);
}
.menu-button {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  height: 2.5rem;
  padding: 0 0.7rem;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  color: var(--color-base-200);
  transition: color 0.15s, background-color 0.15s, border-color 0.15s;
}
.menu-button:hover,
.menu-button.open {
  color: var(--color-base-50);
  border-color: var(--color-base-700);
  background: var(--color-base-800);
}
.menu-panel {
  position: absolute;
  top: calc(100% + 0.5rem);
  left: 1rem;
  width: min(34rem, calc(100vw - 2rem));
  padding: 0.6rem;
  border-radius: 0.9rem;
  border: 1px solid var(--color-base-800);
  background-color: var(--color-base-900);
  box-shadow: 0 18px 50px -12px rgb(0 0 0 / 0.7);
}
@media (min-width: 640px) {
  .menu-panel {
    left: 1.5rem;
  }
}
.menu-link {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.65rem 0.8rem;
  border-radius: 0.55rem;
  font-size: 0.95rem;
  color: var(--color-base-200);
  transition: color 0.15s, background-color 0.15s;
}
.menu-link:hover,
.menu-link:focus-visible {
  color: var(--color-base-50);
  background: var(--color-base-800);
}
.menu-link-sm {
  padding-block: 0.45rem;
  font-size: 0.875rem;
  color: var(--color-base-300);
}
.menu-link[aria-current="page"] {
  color: var(--color-base-50);
  background: color-mix(in srgb, var(--color-redstone-600) 18%, var(--color-base-900));
  box-shadow: inset 3px 0 0 var(--color-redstone-500);
}
.menu-link[aria-current="page"] svg {
  color: var(--color-redstone-400);
}
.menu-enter-active,
.menu-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}
.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
@media (prefers-reduced-motion: reduce) {
  .menu-enter-active,
  .menu-leave-active {
    transition: none;
  }
}
.site-footer {
  background-color: var(--color-base-950);
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
.footer-link {
  color: var(--color-base-400);
  width: fit-content;
}
.footer-link:hover {
  color: var(--color-base-50);
}
/* Redstone-Staub als Trennlinie: rote Leitung mit glühenden Punkten. */
.dust-line {
  height: 4px;
  background:
    radial-gradient(circle, var(--color-redstone-400) 0 1.5px, transparent 2.5px) 0 50% / 24px 4px repeat-x,
    linear-gradient(var(--color-redstone-600), var(--color-redstone-600)) 0 50% / 100% 2px no-repeat;
  box-shadow: 0 0 14px -2px color-mix(in srgb, var(--color-redstone-500) 80%, transparent);
}
</style>
