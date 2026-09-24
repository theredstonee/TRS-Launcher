<script setup lang="ts">
import type { ModrinthVersion, Platform, ProjectCard } from '~/types'

// Abhängigkeiten der gewählten Version (neueste passende bzw. neueste).
const props = defineProps<{ version: ModrinthVersion | null; instanceId: string | null; platform?: Platform }>()
const source = computed<Platform>(() => props.platform ?? 'modrinth')

const cards = ref<Record<string, ProjectCard>>({})
const loading = ref(false)
const error = ref<string | null>(null)

const groups = computed(() => {
  const deps = (props.version?.dependencies ?? []).filter((d) => d.projectId)
  const order = ['required', 'optional', 'incompatible', 'embedded'] as const
  return order
    .map((type) => ({
      type,
      label: t(`project.dependencies.group.${type}`),
      ids: deps.filter((d) => d.dependencyType === type).map((d) => d.projectId!),
    }))
    .filter((g) => g.ids.length)
})

watch(
  () => props.version?.id,
  async () => {
    const ids = [...new Set(groups.value.flatMap((g) => g.ids))].filter((id) => !cards.value[id])
    if (!ids.length) return
    loading.value = true
    error.value = null
    try {
      const list = await platformApi.projects(source.value, ids)
      cards.value = { ...cards.value, ...Object.fromEntries(list.map((c) => [c.projectId, c])) }
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

const tone: Record<string, string> = {
  required: 'bg-redstone-900 text-redstone-300',
  optional: 'bg-base-800 text-base-200',
  incompatible: 'bg-lamp-900 text-lamp-300',
  embedded: 'bg-base-800 text-base-400',
}
</script>

<template>
  <div>
    <p v-if="!version" class="card px-4 py-10 text-center text-sm text-base-400">{{ t('project.dependencies.noVersion') }}</p>
    <p v-else-if="!groups.length" class="card px-4 py-10 text-center text-sm text-base-400">
      {{ t('project.dependencies.none', { version: version.versionNumber }) }}
    </p>
    <template v-else>
      <i18n-t keypath="project.dependencies.intro" tag="p" scope="global" class="mb-3 text-xs text-base-400">
        <template #version><span class="font-mono text-base-200">{{ version.versionNumber }}</span></template>
      </i18n-t>
      <p v-if="error" role="alert" class="mb-3 text-sm text-redstone-300">{{ error }}</p>
      <section v-for="g in groups" :key="g.type" class="mb-5">
        <h3 class="mb-2 text-xs font-medium text-base-400">{{ g.label }}</h3>
        <ul class="grid grid-cols-[repeat(auto-fill,minmax(18rem,1fr))] gap-2">
          <li v-for="id in g.ids" :key="id">
            <div v-if="loading && !cards[id]" class="skeleton h-16" />
            <NuxtLink
              v-else
              :to="projectRoute(source, id, instanceId)"
              class="card card-hover flex items-center gap-3 p-2.5"
            >
              <ModIcon :src="cards[id]?.iconUrl" :name="cards[id]?.title ?? id" :size="40" />
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium">{{ cards[id]?.title ?? id }}</p>
                <p class="truncate text-xs text-base-400">{{ cards[id]?.description ?? '' }}</p>
              </div>
              <span class="badge" :class="tone[g.type]">{{ g.label }}</span>
            </NuxtLink>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
