<template>
  <RouterView v-if="isLoginRoute" :key="viewKey" />
  <div v-else class="app">
    <AppSidebar class="sidebar" />
    <div class="content-wrapper">
      <main class="main-content">
        <RouterView :key="viewKey" />
        <SiteFooter />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute()
const refreshSeq = ref(0)
const isLoginRoute = computed(() => route.name === 'login')
const viewKey = computed(() => `${route.fullPath}:${refreshSeq.value}`)

function refreshCurrentView() {
  refreshSeq.value += 1
}

onMounted(() => {
  window.addEventListener('sql-advisor:refresh-current-view', refreshCurrentView)
})

onBeforeUnmount(() => {
  window.removeEventListener('sql-advisor:refresh-current-view', refreshCurrentView)
})
</script>

<style scoped>
.app {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  width: var(--sidebar-width);
  flex: 0 0 var(--sidebar-width);
}

.content-wrapper {
  flex: 1;
  min-width: 0;
  margin-left: var(--sidebar-width);
}

.main-content {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1.25rem 1.5rem;
}

@media (max-width: 760px) {
  .content-wrapper {
    margin-left: 0;
    padding-top: 4rem;
  }

  .sidebar {
    width: 100%;
  }
}
</style>
