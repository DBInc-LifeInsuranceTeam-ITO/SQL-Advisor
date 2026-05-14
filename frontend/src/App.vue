<template>
  <div class="app">
    <AppSidebar class="sidebar" />
    <div class="content-wrapper">
      <main class="main-content">
        <RouterView />
        <SiteFooter />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import SiteFooter from '@/components/SiteFooter.vue'
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
