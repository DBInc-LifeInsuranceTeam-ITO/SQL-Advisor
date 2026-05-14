<template>
  <aside class="app-sidebar">
    <button class="brand" type="button" @click="go('awr-dashboard')">
      <img class="brand-logo" src="/assets/logo_dblife.png" alt="DB생명" />
      <span class="brand-subtitle">SQL Advisor</span>
    </button>

    <nav class="nav-menu">
      <button
        v-for="item in menuItems"
        :key="item.name"
        type="button"
        :class="['nav-item', { active: isActive(item) }]"
        @click="go(item.name)"
      >
        <span class="nav-icon" aria-hidden="true" v-html="item.icon"></span>
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <div class="sidebar-foot">
      <div class="foot-title">SQL Advisor</div>
      <div class="foot-text">DB Inc. Life Insurance Infra Team</div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const menuItems = [
  {
    name: 'awr-dashboard',
    label: '대시보드',
    icon: '<svg viewBox="0 0 24 24"><path d="M4 13h7V4H4v9Zm0 7h7v-5H4v5Zm9 0h7v-9h-7v9Zm0-16v5h7V4h-7Z"/></svg>'
  },
  {
    name: 'awr-upload',
    label: 'AWR 업로드',
    icon: '<svg viewBox="0 0 24 24"><path d="M12 3 7 8h3v6h4V8h3l-5-5ZM5 19h14v-3h2v5H3v-5h2v3Z"/></svg>'
  },
  {
    name: 'awr-reports',
    label: '리포트 목록',
    icon: '<svg viewBox="0 0 24 24"><path d="M5 3h14v18H5V3Zm3 4v2h8V7H8Zm0 4v2h8v-2H8Zm0 4v2h5v-2H8Z"/></svg>'
  },
  {
    name: 'awr-chat',
    label: 'Advisor Chat',
    icon: '<svg viewBox="0 0 24 24"><path d="M4 4h16v11H7l-3 4V4Zm4 4v2h8V8H8Zm0 4v2h6v-2H8Z"/></svg>'
  }
]

type MenuItem = typeof menuItems[number]

function isActive(item: MenuItem) {
  const current = router.currentRoute.value
  if (item.name === 'awr-reports') {
    return current.name === 'awr-reports' || current.name === 'awr-report-detail'
  }
  if (item.name === 'awr-chat') {
    return current.name === 'awr-chat' || current.name === 'awr-report-chat'
  }
  return current.name === item.name
}

function go(name: string) {
  router.push({ name })
}
</script>

<style scoped>
.app-sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 1000;
  width: var(--sidebar-width);
  min-width: var(--sidebar-width);
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem 0.75rem;
  background: #12161c;
  color: #f7fafc;
}

.brand {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.25rem;
  min-height: 4.35rem;
  padding: 0.65rem 0.5rem;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.brand-logo {
  display: block;
  width: 9.5rem;
  max-width: 100%;
  max-height: 3rem;
  object-fit: contain;
}

.brand-subtitle {
  width: 9.5rem;
  color: #ffffff;
  font-size: 1.58rem;
  font-weight: 500;
  line-height: 1;
  text-align: center;
  white-space: nowrap;
}

.nav-menu {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  width: 100%;
  min-height: 2.65rem;
  padding: 0.55rem 0.65rem;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #d8e1eb;
  cursor: pointer;
  text-align: left;
}

.nav-item:hover {
  background: #222a34;
}

.nav-item.active {
  background: #176ea8;
  color: #ffffff;
}

.nav-icon {
  display: inline-flex;
  width: 1.1rem;
  height: 1.1rem;
  flex: 0 0 1.1rem;
}

.nav-icon :deep(svg) {
  width: 100%;
  height: 100%;
  fill: currentColor;
}

.nav-item span:last-child {
  font-weight: 760;
}

.sidebar-foot {
  margin-top: auto;
  border-top: 1px solid #2d3642;
  padding-top: 0.85rem;
  color: #aab6c3;
}

.foot-title {
  font-size: 0.78rem;
  font-weight: 850;
}

.foot-text {
  margin-top: 0.25rem;
  font-size: 0.72rem;
}

@media (max-width: 760px) {
  .app-sidebar {
    inset: 0 0 auto 0;
    width: 100%;
    min-width: 0;
    height: 4rem;
    flex-direction: row;
    align-items: center;
    padding: 0.55rem 0.75rem;
    overflow-x: auto;
  }

  .brand {
    width: auto;
    min-height: 0;
    flex: 0 0 auto;
    gap: 0.15rem;
    padding: 0.35rem 0.4rem;
  }

  .sidebar-foot {
    display: none;
  }

  .brand-logo {
    width: 5.9rem;
    max-height: 1.9rem;
  }

  .brand-subtitle {
    width: 5.9rem;
    font-size: 0.98rem;
  }

  .nav-menu {
    flex-direction: row;
    flex: 1 0 auto;
  }

  .nav-item {
    width: auto;
    white-space: nowrap;
  }
}
</style>
