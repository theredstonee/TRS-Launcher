<script setup lang="ts">
// Deep-Link /admin/reports/<id>: Prüf-Dialog über der Meldungsliste. Schließen → zurück zur Liste.
const route = useRoute()
const router = useRouter()
const rev = useState('admin-reports-rev', () => 0)
const id = computed(() => String(route.params.id ?? ''))
const valid = computed(() => /^r[0-9a-f]{16}$/.test(id.value))

function close() {
  void router.push('/admin/reports')
}
</script>

<template>
  <ReportReviewDialog
    v-if="valid"
    :report-id="id"
    @close="close"
    @changed="rev++"
    @open="(next: string) => router.push(`/admin/reports/${next}`)"
  />
</template>
