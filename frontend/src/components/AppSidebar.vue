<template>
  <aside class="app-sidebar">
    <button class="brand" type="button" @click="go('awr-dashboard')">
      <img class="brand-logo" src="/assets/logo_dblife.png" alt="DB생명" />
      <span class="brand-subtitle">SQL Advisor</span>
    </button>

    <nav class="nav-menu">
      <button
        v-for="item in visibleMenuItems"
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
      <template v-if="authStore.authEnabled && authStore.user?.authenticated">
        <div class="user-row">
          <img
            v-if="authStore.user.pictureUrl && !avatarLoadFailed"
            class="user-avatar"
            :src="authStore.user.pictureUrl"
            alt=""
            referrerpolicy="no-referrer"
            @error="avatarLoadFailed = true"
          />
          <div v-else class="user-avatar user-avatar-fallback" aria-hidden="true">{{ userInitial }}</div>
          <div class="user-meta">
            <div class="foot-title">{{ authStore.user.displayName || authStore.user.email }}</div>
            <div class="foot-text">{{ authStore.user.role }}</div>
          </div>
        </div>
        <button class="logout-btn" type="button" @click="handleLogout">로그아웃</button>
      </template>
      <template v-else-if="authStore.authEnabled">
        <div class="foot-title">사용자 정보없음</div>
        <div class="foot-text">로그인되어 있지 않습니다</div>
      </template>
      <template v-else>
        <div class="foot-title">SQL Advisor</div>
        <div class="foot-text">DB Inc. Life Insurance Infra Team</div>
      </template>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const avatarLoadFailed = ref(false)
const userInitial = computed(() => {
  const name = authStore.user?.displayName || authStore.user?.email || 'U'
  return name.trim().charAt(0).toUpperCase()
})

watch(() => authStore.user?.pictureUrl, () => {
  avatarLoadFailed.value = false
})

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
  },
  {
    name: 'sql-tuning',
    label: 'SQL 튜닝',
    icon: '<svg viewBox="0 0 24 24"><path d="M4 5h16v14H4V5Zm2 2v10h12V7H6Zm2 2h5v2H8V9Zm0 3h8v2H8v-2Z"/></svg>'
  },
  {
    name: 'awr-ai-settings',
    label: 'AI 설정',
    icon: '<svg viewBox="0 0 24 24"><path d="M19.4 13.5c.1-.5.1-1 .1-1.5s0-1-.1-1.5l2-1.5-2-3.5-2.4 1a7.5 7.5 0 0 0-2.6-1.5L14 2h-4l-.4 3a7.5 7.5 0 0 0-2.6 1.5l-2.4-1-2 3.5 2 1.5A8 8 0 0 0 4.5 12c0 .5 0 1 .1 1.5l-2 1.5 2 3.5 2.4-1a7.5 7.5 0 0 0 2.6 1.5l.4 3h4l.4-3a7.5 7.5 0 0 0 2.6-1.5l2.4 1 2-3.5-2-1.5ZM12 15.5A3.5 3.5 0 1 1 12 8a3.5 3.5 0 0 1 0 7.5Z"/></svg>'
  }
]

type MenuItem = typeof menuItems[number]

const visibleMenuItems = computed(() => {
  if (authStore.authEnabled && !authStore.isAuthenticated) {
    return menuItems.filter((item) => item.name === 'awr-dashboard')
  }
  return menuItems
})

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

async function handleLogout() {
  await authStore.logout()
  if (authStore.authMode === 'internal') {
    return
  }
  router.push({ name: 'login' })
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
  background: #ffffff;
  color: #143225;
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
  color: #00854A;
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
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: #214438;
  cursor: pointer;
  text-align: left;
}

.nav-item:hover {
  border-color: #b9d8c9;
  background: #eef8f3;
  color: #006d3d;
}

.nav-item.active {
  border-color: #00854A;
  background: #00854A;
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
  border-top: 1px solid #d7e8df;
  padding-top: 0.85rem;
  color: #5d756b;
}

.foot-title {
  font-size: 0.78rem;
  font-weight: 850;
}

.foot-text {
  margin-top: 0.25rem;
  font-size: 0.72rem;
}

.user-row {
  display: flex;
  align-items: center;
  gap: 0.55rem;
}

.user-avatar {
  display: inline-grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  flex: 0 0 2rem;
  border-radius: 50%;
  object-fit: cover;
}

.user-avatar-fallback {
  background: #00854A;
  color: #ffffff;
  font-size: 0.82rem;
  font-weight: 900;
}

.user-meta {
  min-width: 0;
}

.user-meta .foot-title,
.user-meta .foot-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout-btn {
  width: 100%;
  min-height: 2.2rem;
  margin-top: 0.7rem;
  border: 1px solid #b9d8c9;
  border-radius: 8px;
  background: #ffffff;
  color: #00854A;
  font-weight: 800;
  cursor: pointer;
}

.logout-btn:hover {
  background: #e6f4ee;
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
