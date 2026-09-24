<script setup lang="ts">
import type { NuxtError } from '#app'

const props = defineProps<{ error: NuxtError }>()
const { m } = useLang()
const is404 = computed(() => props.error.statusCode === 404)
useHead({ title: () => (is404.value ? m.value.notFound.title : 'Error'), meta: [{ name: 'robots', content: 'noindex' }] })
</script>

<template>
  <NuxtLayout>
    <div class="relative isolate mx-auto grid max-w-3xl place-items-center overflow-hidden px-4 py-28 text-center sm:px-6">
      <p class="display text-8xl text-redstone-500 drop-shadow">{{ error.statusCode || 500 }}</p>
      <h1 class="display mt-4 text-4xl text-base-50">{{ is404 ? m.notFound.title : m.common.error }}</h1>
      <p v-if="is404" class="mt-3 text-base-400">{{ m.notFound.text }}</p>
      <button type="button" class="btn btn-primary mt-8" @click="clearError({ redirect: '/' })">{{ m.notFound.home }}</button>
    </div>
  </NuxtLayout>
</template>
