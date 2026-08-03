<template>
  <RouterView v-if="isLoginRoute" :key="viewKey" />

  <div v-else class="app">
    <AppSidebar class="sidebar" />

    <div class="content-wrapper">
      <main
        :class="[
          'main-content',
          { 'dashboard-main-content': isDashboardRoute }
        ]"
      >
        <RouterView :key="viewKey" />
        <SiteFooter v-if="!isDashboardRoute" />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref
} from 'vue'
import {
  RouterView,
  useRoute
} from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import SiteFooter from '@/components/SiteFooter.vue'

const route = useRoute()
const refreshSeq = ref(0)

const isLoginRoute = computed(
  () => route.name === 'login'
)

const isDashboardRoute = computed(
  () => route.name === 'awr-dashboard'
)

const viewKey = computed(
  () => `${route.fullPath}:${refreshSeq.value}`
)

function refreshCurrentView() {
  refreshSeq.value += 1
}

onMounted(() => {
  window.addEventListener(
    'sql-advisor:refresh-current-view',
    refreshCurrentView
  )
})

onBeforeUnmount(() => {
  window.removeEventListener(
    'sql-advisor:refresh-current-view',
    refreshCurrentView
  )
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
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  gap: 1rem;
  padding: 1.25rem 1.5rem;
}

.main-content.dashboard-main-content {
  height: 100vh;
  min-height: 100vh;
  gap: 0;
  padding: 0;
  overflow: hidden;
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

<style>
/*
 * 모든 화면의 메인 제목을 AWRChat의 "리포트 질의"과 동일하게 표시
 *
 * 기존 awr.css의 .awr-main-title에는
 * font-weight: 950, 좁은 자간, text-shadow가 들어 있어
 * 같은 크기를 지정해도 더 두껍게 보일 수 있으므로 !important로 제거한다.
 */
.awr-main-title,
.awr-page-header h1,
.dashboard-header h1,
.admin-hero h1,
.awr-chat-heading h1 {
  margin: 0 !important;
  color: var(--awr-title) !important;
  font-size: 1.75rem !important;
  font-weight: 900 !important;
  line-height: 1.2 !important;
  letter-spacing: -0.03em !important;
  text-shadow: none !important;
  font-family: inherit !important;
}

@media (max-width: 720px) {
  .awr-main-title,
  .awr-page-header h1,
  .dashboard-header h1,
  .admin-hero h1,
  .awr-chat-heading h1 {
    font-size: 1.45rem !important;
  }
}
</style>
