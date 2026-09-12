import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken } from '@/utils/auth'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    redirect: '/dashboard',
    meta: { requiresAuth: true },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '数据看板', icon: 'DataAnalysis' }
      },
      {
        path: 'user',
        name: 'UserManage',
        component: () => import('@/views/user/UserManage.vue'),
        meta: { title: '用户管理', icon: 'User', roles: ['ADMIN'] }
      },
      {
        path: 'clazz',
        name: 'ClassManage',
        component: () => import('@/views/clazz/ClassManage.vue'),
        meta: { title: '班级管理', icon: 'OfficeBuilding' }
      },
      {
        path: 'student',
        name: 'StudentManage',
        component: () => import('@/views/student/StudentManage.vue'),
        meta: { title: '学生管理', icon: 'Avatar' }
      },
      {
        path: 'product',
        name: 'ProductManage',
        component: () => import('@/views/product/ProductManage.vue'),
        meta: { title: '奶品管理', icon: 'Goods', roles: ['ADMIN'] }
      },
      {
        path: 'order',
        name: 'OrderManage',
        component: () => import('@/views/order/OrderManage.vue'),
        meta: { title: '订单管理', icon: 'List' }
      },
      {
        path: 'delivery',
        name: 'DeliveryManage',
        component: () => import('@/views/delivery/DeliveryManage.vue'),
        meta: { title: '配送管理', icon: 'Van' }
      },
      {
        path: 'nutrition',
        name: 'NutritionStats',
        component: () => import('@/views/nutrition/NutritionStats.vue'),
        meta: { title: '营养统计', icon: 'Histogram' }
      },
      {
        path: 'subscription',
        name: 'SubscriptionManage',
        component: () => import('@/views/subscription/SubscriptionManage.vue'),
        meta: { title: '自动续订', icon: 'RefreshRight', roles: ['ADMIN'] }
      },
      {
        path: 'system',
        name: 'SystemManage',
        component: () => import('@/views/system/SystemManage.vue'),
        meta: { title: '系统管理', icon: 'Setting', roles: ['ADMIN'] }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const token = getToken()
  const requiresAuth = to.meta.requiresAuth !== false

  if (requiresAuth && !token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  if (to.path === '/login' && token) {
    next({ path: '/' })
    return
  }
  // 角色校验：meta.roles 配置了允许角色时，当前用户至少需拥有其一
  const requiredRoles = to.meta.roles as string[] | undefined
  if (requiredRoles?.length) {
    const roles = useUserStore().roles
    if (!roles.some((r) => requiredRoles.includes(r))) {
      ElMessage.error('无权访问该页面')
      next({ path: '/dashboard' })
      return
    }
  }
  next()
})

router.afterEach((to) => {
  document.title = (to.meta.title ? `${to.meta.title} - ` : '') + '学生奶订购系统'
})

export default router
