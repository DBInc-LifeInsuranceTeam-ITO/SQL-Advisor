import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/dashboard'
    },
    {
      path: '/dashboard',
      name: 'awr-dashboard',
      component: () => import('@/views/awr/AwrDashboard.vue')
    },
    {
      path: '/upload',
      name: 'awr-upload',
      component: () => import('@/views/awr/AwrUpload.vue')
    },
    {
      path: '/reports',
      name: 'awr-reports',
      component: () => import('@/views/awr/AwrReportList.vue')
    },
    {
      path: '/reports/:id',
      name: 'awr-report-detail',
      component: () => import('@/views/awr/AwrReportDetail.vue'),
      props: true
    },
    {
      path: '/reports/:id/chat',
      name: 'awr-report-chat',
      component: () => import('@/views/awr/AwrChat.vue'),
      props: true
    },
    {
      path: '/chat',
      name: 'awr-chat',
      component: () => import('@/views/awr/AwrChat.vue')
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/dashboard'
    }
  ]
})

export default router
