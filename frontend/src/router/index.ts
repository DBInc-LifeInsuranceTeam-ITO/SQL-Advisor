import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

type UserRole = 'ADMIN' | 'USER' | 'MONITOR'

function normalizeRole(role?: string | null): UserRole {
  const normalized = role?.trim().toUpperCase()

  if (normalized === 'ADMIN') return 'ADMIN'
  if (normalized === 'MONITOR') return 'MONITOR'

  return 'USER'
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/dashboard'
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/AuthCallbackView.vue')
    },
    {
      path: '/dashboard',
      name: 'awr-dashboard',
      meta: { requiresAuth: true, roles: ['ADMIN', 'USER', 'MONITOR'] },
      component: () => import('@/views/awr/AwrDashboard.vue')
    },
    {
      path: '/upload',
      name: 'awr-upload',
      meta: { requiresAuth: true, roles: ['ADMIN', 'USER'] },
      component: () => import('@/views/awr/AwrUpload.vue')
    },
    {
      path: '/reports',
      name: 'awr-reports',
      meta: { requiresAuth: true, roles: ['ADMIN', 'USER', 'MONITOR'] },
      component: () => import('@/views/awr/AwrReportList.vue')
    },
    {
      path: '/reports/:id',
      name: 'awr-report-detail',
      meta: { requiresAuth: true, roles: ['ADMIN', 'USER', 'MONITOR'] },
      component: () => import('@/views/awr/AwrReportDetail.vue'),
      props: true
    },
    {
      path: '/reports/:id/chat',
      name: 'awr-report-chat',
      meta: { requiresAuth: true, roles: ['ADMIN', 'USER'] },
      component: () => import('@/views/awr/AwrChat.vue'),
      props: true
    },
    {
      path: '/chat',
      name: 'awr-chat',
      meta: { requiresAuth: true, roles: ['ADMIN', 'USER'] },
      component: () => import('@/views/awr/AwrChat.vue')
    },
    {
      path: '/sql-tuning',
      name: 'sql-tuning',
      meta: { requiresAuth: true, roles: ['ADMIN', 'USER', 'MONITOR'] },
      component: () => import('@/views/sql-tuning/SqlTuningWorkbench.vue')
    },
    {
      path: '/settings/ai',
      name: 'awr-ai-settings',
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      component: () => import('@/views/awr/AwrAiSettings.vue')
    },
    {
      path: '/settings/users',
      name: 'user-management',
      meta: { requiresAuth: true, roles: ['ADMIN'] },
      component: () => import('@/views/awr/UserManagement.vue')
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/dashboard'
    }
  ]
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  await authStore.initialize()

  if (to.name === 'login') {
    if (!authStore.authEnabled || authStore.isAuthenticated) {
      return { name: 'awr-dashboard' }
    }

    return true
  }

  if (to.meta.requiresAuth && authStore.authEnabled && !authStore.isAuthenticated) {
    if (authStore.internalLoginEnabled) {
      return true
    }

    return {
      name: 'login',
      query: { redirect: to.fullPath }
    }
  }

  const allowedRoles = to.meta.roles as UserRole[] | undefined

  if (authStore.authEnabled && authStore.isAuthenticated && allowedRoles?.length) {
    const currentRole = normalizeRole(authStore.user?.role)

    if (!allowedRoles.includes(currentRole)) {
      return { name: 'awr-dashboard' }
    }
  }

  return true
})

export default router
