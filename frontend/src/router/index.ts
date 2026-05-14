import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
      component: () => import('@/views/LoginView.vue')
    },
    {
      path: '/dashboard',
      name: 'awr-dashboard',
      meta: { requiresAuth: true },
      component: () => import('@/views/awr/AwrDashboard.vue')
    },
    {
      path: '/upload',
      name: 'awr-upload',
      meta: { requiresAuth: true },
      component: () => import('@/views/awr/AwrUpload.vue')
    },
    {
      path: '/reports',
      name: 'awr-reports',
      meta: { requiresAuth: true },
      component: () => import('@/views/awr/AwrReportList.vue')
    },
    {
      path: '/reports/:id',
      name: 'awr-report-detail',
      meta: { requiresAuth: true },
      component: () => import('@/views/awr/AwrReportDetail.vue'),
      props: true
    },
    {
      path: '/reports/:id/chat',
      name: 'awr-report-chat',
      meta: { requiresAuth: true },
      component: () => import('@/views/awr/AwrChat.vue'),
      props: true
    },
    {
      path: '/chat',
      name: 'awr-chat',
      meta: { requiresAuth: true },
      component: () => import('@/views/awr/AwrChat.vue')
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
    return {
      name: 'login',
      query: { redirect: to.fullPath }
    }
  }

  return true
})

export default router
