<script setup lang="ts">
const { m } = useLang()
const route = useRoute()
const menuOpen = ref(false)

const links = computed(() => [
  { to: '/download', label: m.value.nav.download },
  { to: '/blog', label: m.value.nav.blog },
  { to: '/capes', label: m.value.nav.capes },
  { to: '/faq', label: m.value.nav.faq },
])

watch(() => route.path, () => (menuOpen.value = false))
const year = new Date().getFullYear()
</script>

<template>
  <div class="flex min-h-screen flex-col bg-base-950 text-base-200">
    <a href="#main" class="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 btn btn-primary">Skip to content</a>

    <header class="site-header sticky top-0 z-30">
      <div class="mx-auto flex h-16 max-w-6xl items-center gap-4 px-4 sm:px-6">
        <NuxtLink to="/" class="flex items-center gap-2.5 text-base-50" aria-label="TRS Launcher">
          <img src="/icon.png" alt="" width="32" height="32" class="size-8 [image-rendering:pixelated]" />
          <span class="display text-xl leading-none">TRS Launcher</span>
        </NuxtLink>

        <nav class="ml-4 hidden items-center gap-1 md:flex" :aria-label="m.nav.menu">
          <NuxtLink v-for="l in links" :key="l.to" :to="l.to" class="nav-link">{{ l.label }}</NuxtLink>
        </nav>

        <div class="ml-auto flex items-center gap-2">
          <LangSwitch />
          <a :href="REPO_URL" class="btn-icon hidden sm:inline-flex" aria-label="GitHub" rel="noopener" target="_blank">
            <SiteIcon name="github" class="size-4.5" />
          </a>
          <NuxtLink to="/download" class="btn btn-primary hidden sm:inline-flex">
            <SiteIcon name="download" class="size-4" />
            {{ m.nav.download }}
          </NuxtLink>
          <button
            type="button"
            class="btn-icon md:hidden"
            :aria-label="m.nav.menu"
            :aria-expanded="menuOpen"
            @click="menuOpen = !menuOpen"
          >
            <SiteIcon :name="menuOpen ? 'close' : 'menu'" class="size-5" />
          </button>
        </div>
      </div>
      <nav v-if="menuOpen" class="border-t border-base-800 px-4 pb-4 md:hidden" :aria-label="m.nav.menu">
        <NuxtLink v-for="l in links" :key="l.to" :to="l.to" class="block rounded-md px-3 py-2.5 text-base text-base-200 hover:bg-base-800">
          {{ l.label }}
        </NuxtLink>
        <NuxtLink to="/download" class="btn btn-primary mt-2 w-full">{{ m.nav.download }}</NuxtLink>
      </nav>
    </header>

    <main id="main" class="flex-1">
      <slot />
    </main>

    <footer class="site-footer mt-24">
      <div class="dust-line" aria-hidden="true" />
      <div class="mx-auto grid max-w-6xl gap-10 px-4 py-12 sm:px-6 md:grid-cols-[1.4fr_1fr_1fr]">
        <div>
          <div class="flex items-center gap-2.5 text-base-50">
            <img src="/icon.png" alt="" width="28" height="28" class="size-7 [image-rendering:pixelated]" />
            <span class="display text-lg">TRS Launcher</span>
          </div>
          <p class="mt-3 max-w-sm text-sm text-base-400">{{ m.footer.tagline }}</p>
          <p class="mt-3 max-w-sm text-xs text-base-600">{{ m.footer.notAffiliated }}</p>
        </div>
        <nav class="flex flex-col gap-2 text-sm" aria-label="Site">
          <NuxtLink to="/download" class="footer-link">{{ m.nav.download }}</NuxtLink>
          <NuxtLink to="/blog" class="footer-link">{{ m.nav.blog }}</NuxtLink>
          <NuxtLink to="/capes" class="footer-link">{{ m.nav.capes }}</NuxtLink>
          <NuxtLink to="/faq" class="footer-link">{{ m.nav.faq }}</NuxtLink>
        </nav>
        <nav class="flex flex-col gap-2 text-sm" aria-label="Links">
          <a :href="REPO_URL" class="footer-link" rel="noopener" target="_blank">{{ m.footer.github }}</a>
          <a :href="DISCORD_URL" class="footer-link" rel="noopener" target="_blank">{{ m.footer.discord }}</a>
          <a :href="WIKI_URL" class="footer-link" rel="noopener" target="_blank">{{ m.footer.wiki }}</a>
          <a :href="IMPRINT_URL" class="footer-link" rel="noopener">{{ m.footer.imprint }}</a>
          <NuxtLink to="/privacy" class="footer-link">{{ m.footer.privacy }}</NuxtLink>
        </nav>
      </div>
      <div class="border-t border-base-800">
        <div class="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-2 px-4 py-4 text-xs text-base-600 sm:px-6">
          <span>© {{ year }} Theredstonee · GPL-3.0</span>
          <NuxtLink to="/admin" class="hover:text-base-400">{{ m.nav.admin }}</NuxtLink>
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
.nav-link {
  border-radius: 0.375rem;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  color: var(--color-base-400);
  transition: color 0.15s, background-color 0.15s;
}
.nav-link:hover {
  color: var(--color-base-50);
  background: var(--color-base-900);
}
.nav-link.router-link-active {
  color: var(--color-base-50);
  box-shadow: inset 0 -2px 0 var(--color-redstone-500);
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
