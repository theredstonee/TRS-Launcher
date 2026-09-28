<script setup lang="ts">
// Eigene und gefolgte Issues (§28). Nur im Browser, nie indexiert.
import type { IssueView } from '#shared/issues'

const { it, date, errorText } = useIssueText()
const lp = useLocalePath()
const { account, load, api, loginUrl } = useAccount()
const loaded = ref(false)
const data = ref<{ created: IssueView[], following: IssueView[] } | null>(null)
const error = ref('')
const tab = ref<'following' | 'created'>('following')

onMounted(async () => {
  if (await load()) {
    try {
      data.value = await api('/v1/me/issues')
    } catch (e) {
      error.value = errorText(e)
    }
  }
  loaded.value = true
})
const list = computed(() => (tab.value === 'created' ? data.value?.created : data.value?.following) ?? [])

async function unfollow(i: IssueView) {
  try {
    await api(`/v1/issues/${i.number}/follow`, { method: 'DELETE' })
    if (data.value) data.value.following = data.value.following.filter((x) => x.number !== i.number)
  } catch (e) {
    error.value = errorText(e)
  }
}

useHead({ title: () => it.value.seo.mine, meta: [{ name: 'robots', content: 'noindex, nofollow' }] })
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pt-8 pb-6 sm:px-6">
    <NuxtLink :to="lp('/issues')" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-100">
      <SiteIcon name="back" class="size-4" />{{ it.detail.back }}
    </NuxtLink>
    <h1 class="display mt-4 text-4xl text-base-50">{{ it.mine.title }}</h1>
    <p class="mt-2 text-base-400">{{ it.mine.lead }}</p>

    <p v-if="!loaded" class="mt-10 text-base-400">{{ it.common.loading }}</p>
    <a v-else-if="!account" :href="loginUrl()" class="btn btn-primary mt-8"><SiteIcon name="microsoft" class="size-4" />{{ it.common.signIn }}</a>
    <template v-else>
      <div class="mt-6 flex gap-1 border-b border-base-800" role="tablist">
        <button type="button" role="tab" class="tab-btn" :aria-selected="tab === 'following'" @click="tab = 'following'">{{ it.mine.following }} <span class="text-base-400">{{ data?.following.length ?? 0 }}</span></button>
        <button type="button" role="tab" class="tab-btn" :aria-selected="tab === 'created'" @click="tab = 'created'">{{ it.mine.created }} <span class="text-base-400">{{ data?.created.length ?? 0 }}</span></button>
      </div>
      <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
      <p v-if="!list.length" class="mt-8 text-base-400">{{ tab === 'created' ? it.mine.emptyCreated : it.mine.emptyFollowing }}</p>
      <ul v-else class="card mt-4 divide-y divide-base-800">
        <li v-for="i in list" :key="i.number" class="flex flex-wrap items-center gap-3 px-4 py-3">
          <SiteIcon :name="i.type === 'bug' ? 'bug' : 'bolt'" class="size-4.5 shrink-0" :class="i.type === 'bug' ? 'text-redstone-300' : 'text-lamp-300'" />
          <NuxtLink :to="lp(`/issues/${i.number}`)" class="min-w-0 flex-1 font-medium text-base-50 hover:text-redstone-300">
            {{ i.title }} <span class="text-base-400">#{{ i.number }}</span>
          </NuxtLink>
          <IssueStatus :status="i.status" />
          <span class="text-xs text-base-400">{{ date(i.activityAt) }}</span>
          <button v-if="tab === 'following'" type="button" class="btn btn-ghost px-2 py-1 text-xs" @click="unfollow(i)">{{ it.mine.unfollow }}</button>
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.tab-btn {
  padding: 0.6rem 0.9rem;
  font-size: 0.875rem;
  color: var(--color-base-400);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.tab-btn[aria-selected='true'] {
  color: var(--color-base-50);
  border-bottom-color: var(--color-redstone-500);
}
</style>
