<script setup lang="ts">
// Sprachumschalter: Kürzel statt Flaggen-Emoji (Windows zeigt die nur als Buchstaben) – gleiche Seite in der
// anderen Sprache. Gibt es die Seite dort (noch) nicht, führt der Link auf die Startseite der Sprache (keine 404).
import pages from '#docs-pages'

const { locale, locales, switchLocalePath } = useDocusI18n()
const existing = new Set(pages)

function target(code: string): string {
  const path = (switchLocalePath(code) as string) || `/${code}`
  return existing.has(path.replace(/\/+$/, '')) ? path : `/${code}`
}
</script>

<template>
  <UPopover :content="{ align: 'end' }">
    <UButton
      color="neutral"
      variant="ghost"
      icon="i-lucide-languages"
      :label="locale.toUpperCase()"
      :aria-label="locales.find((l) => l.code === locale)?.name ?? locale"
    />

    <template #content>
      <ul class="flex min-w-36 flex-col p-1">
        <li v-for="item in locales" :key="item.code">
          <NuxtLink
            class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5 text-sm hover:bg-elevated"
            :class="item.code === locale ? 'text-primary' : 'text-default'"
            :to="target(item.code)"
            :hreflang="item.code"
            :lang="item.code"
          >
            <span>{{ item.name }}</span>
            <span class="text-xs text-dimmed">{{ item.code.toUpperCase() }}</span>
          </NuxtLink>
        </li>
      </ul>
    </template>
  </UPopover>
</template>
