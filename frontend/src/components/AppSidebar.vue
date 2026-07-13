<template>
  <aside class="app-sidebar">
    <button class="brand" type="button" @click="goHome">
      <img class="brand-logo" src="/assets/logo_dblife.png" alt="DB생명" />
      <span class="brand-subtitle">SQL Advisor</span>
    </button>

<nav class="nav-menu">
  <!-- 단일 메뉴 -->
  <button
    v-for="item in visibleDirectMenuItems"
    :key="item.name"
    type="button"
    :class="['nav-item', { active: isActive(item) }]"
    @click="go(item.name)"
  >
    <span class="nav-icon" aria-hidden="true" v-html="item.icon"></span>
    <span class="nav-label">{{ item.label }}</span>
  </button>

  <!-- AWR 분석 -->
  <div v-if="visibleAwrMenuItems.length > 0" class="nav-group">
    <button
      type="button"
      :class="['nav-item', 'nav-group-title']"
      @click="awrMenuOpen = !awrMenuOpen"
    >
      <span class="nav-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M4 4h16v16H4V4Zm3 3v2h10V7H7Zm0 4v2h10v-2H7Zm0 4v2h7v-2H7Z" />
        </svg>
      </span>

      <span class="nav-label">AWR 분석</span>

      <span :class="['nav-arrow', { open: awrMenuOpen }]" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="m7 10 5 5 5-5H7Z" />
        </svg>
      </span>
    </button>

    <div v-show="awrMenuOpen" class="nav-submenu">
      <button
        v-for="item in visibleAwrMenuItems"
        :key="item.name"
        type="button"
        :class="['nav-item', 'nav-subitem', { active: isActive(item) }]"
        @click="go(item.name)"
      >
        <span class="nav-tree-line" aria-hidden="true"></span>
        <span class="nav-label">{{ item.label }}</span>
      </button>
    </div>
  </div>

  <!-- 시스템 관리 -->
  <div v-if="visibleSystemMenuItems.length > 0" class="nav-group">
    <button
      type="button"
      :class="[
        'nav-item',
        'nav-group-title'
      ]"
      @click="systemMenuOpen = !systemMenuOpen"
    >
      <span class="nav-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M19.4 13.5c.1-.5.1-1 .1-1.5s0-1-.1-1.5l2-1.5-2-3.5-2.4 1a7.5 7.5 0 0 0-2.6-1.5L14 2h-4l-.4 3a7.5 7.5 0 0 0-2.6 1.5l-2.4-1-2 3.5 2 1.5A8 8 0 0 0 4.5 12c0 .5 0 1 .1 1.5l-2 1.5 2 3.5 2.4-1a7.5 7.5 0 0 0 2.6 1.5l.4 3h4l.4-3a7.5 7.5 0 0 0 2.6-1.5l2.4 1 2-3.5-2-1.5ZM12 15.5A3.5 3.5 0 1 1 12 8a3.5 3.5 0 0 1 0 7.5Z" />
        </svg>
      </span>

      <span class="nav-label">시스템 관리</span>

      <span :class="['nav-arrow', { open: systemMenuOpen }]" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="m7 10 5 5 5-5H7Z" />
        </svg>
      </span>
    </button>

    <div v-show="systemMenuOpen" class="nav-submenu">
      <button
        v-for="item in visibleSystemMenuItems"
        :key="item.name"
        type="button"
        :class="['nav-item', 'nav-subitem', { active: isActive(item) }]"
        @click="go(item.name)"
      >
        <span class="nav-tree-line" aria-hidden="true"></span>
        <span class="nav-label">{{ item.label }}</span>
      </button>
    </div>
  </div>
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
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const avatarLoadFailed = ref(false)
const awrMenuOpen = ref(false)
const systemMenuOpen = ref(false)
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
    name: 'sql-tuning',
    label: 'SQL 튜닝',
    icon: '<svg viewBox="0 0 24 24"><path d="M4 5h16v14H4V5Zm2 2v10h12V7H6Zm2 2h5v2H8V9Zm0 3h8v2H8v-2Z"/></svg>'
  },
  {
    name: 'awr-chat',
    label: 'AI 리포트 분석',
    icon: '<svg viewBox="0 0 24 24"><path d="M4 4h16v11H7l-3 4V4Zm4 4v2h8V8H8Zm0 4v2h6v-2H8Z"/></svg>'
  },
  {
    name: 'awr-upload',
    label: '분석 요청',
    icon: '<svg viewBox="0 0 24 24"><path d="M12 3 7 8h3v6h4V8h3l-5-5ZM5 19h14v-3h2v5H3v-5h2v3Z"/></svg>'
  },
  {
    name: 'awr-reports',
    label: '분석 결과',
    icon: '<svg viewBox="0 0 24 24"><path d="M5 3h14v18H5V3Zm3 4v2h8V7H8Zm0 4v2h8v-2H8Zm0 4v2h5v-2H8Z"/></svg>'
  },
  {
    name: 'awr-ai-settings',
    label: 'AI 연동 설정',
    icon: '<svg viewBox="0 0 24 24"><path d="M19.4 13.5c.1-.5.1-1 .1-1.5s0-1-.1-1.5l2-1.5-2-3.5-2.4 1a7.5 7.5 0 0 0-2.6-1.5L14 2h-4l-.4 3a7.5 7.5 0 0 0-2.6 1.5l-2.4-1-2 3.5 2 1.5A8 8 0 0 0 4.5 12c0 .5 0 1 .1 1.5l-2 1.5 2 3.5 2.4-1a7.5 7.5 0 0 0 2.6 1.5l.4 3h4l.4-3a7.5 7.5 0 0 0 2.6-1.5l2.4 1 2-3.5-2-1.5ZM12 15.5A3.5 3.5 0 1 1 12 8a3.5 3.5 0 0 1 0 7.5Z"/></svg>'
  },
  {
    name: 'user-management',
    label: '사용자 권한 관리',
    icon: '<svg viewBox="0 0 24 24"><path d="M16 11c1.7 0 3-1.3 3-3s-1.3-3-3-3-3 1.3-3 3 1.3 3 3 3ZM8 12c1.7 0 3-1.3 3-3S9.7 6 8 6 5 7.3 5 9s1.3 3 3 3Zm8 2c-2 0-6 1-6 3v1h12v-1c0-2-4-3-6-3Zm-8 1c-1.9 0-6 .9-6 3v1h6v-1c0-1 .6-2 1.6-2.8A8 8 0 0 0 8 15Z"/></svg>'
  }
]

type MenuItem = typeof menuItems[number]
const directMenuNames = [
  'awr-dashboard',
  'sql-tuning',
  'awr-chat'
]

const awrMenuNames = [
  'awr-upload',
  'awr-reports'
]

const systemMenuNames = [
  'awr-ai-settings',
  'user-management'
]

const visibleMenuItems = computed(() => {
  if (authStore.authEnabled && !authStore.isAuthenticated) {
    return menuItems.filter((item) => item.name === 'awr-dashboard')
  }

  if (authStore.isAdmin) {
    return menuItems
  }

  if (authStore.isMonitor) {
    return menuItems.filter((item) =>
      ['awr-dashboard', 'awr-reports', 'sql-tuning'].includes(item.name)
    )
  }

  return menuItems.filter((item) =>
    !['awr-ai-settings', 'user-management'].includes(item.name)
  )
})
const visibleDirectMenuItems = computed(() =>
  visibleMenuItems.value.filter((item) =>
    directMenuNames.includes(item.name)
  )
)

const visibleAwrMenuItems = computed(() =>
  visibleMenuItems.value.filter((item) =>
    awrMenuNames.includes(item.name)
  )
)

const visibleSystemMenuItems = computed(() =>
  visibleMenuItems.value.filter((item) =>
    systemMenuNames.includes(item.name)
  )
)

function isActive(item: MenuItem) {
  const current = route
  if (item.name === 'awr-reports') {
    return current.name === 'awr-reports' || current.name === 'awr-report-detail'
  }
  if (item.name === 'awr-chat') {
    return current.name === 'awr-chat' || current.name === 'awr-report-chat'
  }
  return current.name === item.name
}

function closeAllMenus() {
  awrMenuOpen.value = false
  systemMenuOpen.value = false
}

async function goHome() {
  closeAllMenus()

  if (route.name !== 'awr-dashboard') {
    await router.push({ name: 'awr-dashboard' })
  }

  window.dispatchEvent(new CustomEvent('sql-advisor:refresh-current-view'))
}

async function go(name: string) {
  const isSameRoute = route.name === name

  if (isSameRoute) {
    window.dispatchEvent(new CustomEvent('sql-advisor:refresh-current-view'))
    return
  }

  await router.push({ name })
}


async function handleLogout() {
  await authStore.logout()
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
  gap: 0.2rem;
  min-height: 4.35rem;
  padding: 0.65rem 0.5rem;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
  text-decoration: none;
}

.brand-logo {
  display: block;
  width: 9.5rem;
  max-width: 100%;
  max-height: 3rem;
  object-fit: contain;
}

.brand-subtitle {
  display: block;
  width: auto;
  margin-left: 1.05rem;
  padding-left: 0;
  color: #214438;
  font-size: 1.28rem;
  font-weight: 800;
  line-height: 1.05;
  letter-spacing: -0.025em;
  text-align: left;
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
  text-decoration: none;
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


.nav-item {
  font-family: inherit;
  font-size: 0.95rem;
}

.nav-label {
  font-family: inherit;
  font-size: inherit;
  font-weight: 800 !important;
  line-height: 1.2;
}

.nav-group {
  display: flex;
  flex-direction: column;
}

.nav-group-title {
  position: relative;
}

.nav-arrow {
  display: inline-flex;
  width: 1rem;
  height: 1rem;
  margin-left: auto;
  transition: transform 0.2s ease;
}

.nav-arrow.open {
  transform: rotate(180deg);
}

.nav-arrow svg {
  width: 100%;
  height: 100%;
  fill: currentColor;
}

.nav-submenu {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  margin-top: 0.1rem;
  padding-left: 1.9rem;
}

.nav-subitem {
  width: calc(100% - 0.45rem);
  min-height: 2.2rem;
  padding: 0.35rem 0.55rem;
  color: #214438;
}

.nav-subitem .nav-label {
  font-size: 0.88rem;
}

.nav-tree-line {
  display: none;
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
