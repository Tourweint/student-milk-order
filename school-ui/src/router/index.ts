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
        meta: { title: '数据看板', icon: 'DataAnalysis', roles: ['ADMIN', 'TEACHER'] }
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
        meta: { title: '班级管理', icon: 'OfficeBuilding', roles: ['ADMIN', 'TEACHER'] }
      },
      {
        path: 'student',
        name: 'StudentManage',
        component: () => import('@/views/student/StudentManage.vue'),
        meta: { title: '学生管理', icon: 'Avatar', roles: ['ADMIN', 'TEACHER'] }
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
        meta: { title: '订单管理', icon: 'List', roles: ['ADMIN', 'TEACHER'] }
      },
      {
        path: 'delivery-station',
        name: 'DeliveryStation',
        component: () => import('@/views/delivery/DeliveryStation.vue'),
        meta: { title: '配送站面板', icon: 'Box', roles: ['ADMIN', 'DELIVERY'] }
      },
      {
        path: 'delivery',
        name: 'DeliveryManage',
        component: () => import('@/views/delivery/DeliveryManage.vue'),
        meta: { title: '配送管理', icon: 'Van', roles: ['ADMIN', 'TEACHER'] }
      },
      {
        path: 'delivery-calendar',
        name: 'DeliveryCalendar',
        component: () => import('@/views/delivery/DeliveryCalendar.vue'),
        meta: { title: '配送日历', icon: 'Calendar', roles: ['ADMIN'] }
      },
      {
        path: 'nutrition',
        name: 'NutritionStats',
        component: () => import('@/views/nutrition/NutritionStats.vue'),
        meta: { title: '营养统计', icon: 'Histogram', roles: ['ADMIN', 'TEACHER'] }
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

// 当前用户有权访问的第一个菜单页（角色不满足时的回退目标，避免硬编码 /dashboard 造成回退死循环）
function firstAllowedPath(): string {
  const roles = useUserStore().roles
  const children = routes.find((r) => r.path === '/')?.children ?? []
  const allowed = children.find((m) => {
    const mr = m.meta?.roles as string[] | undefined
    return !mr?.length || mr.some((r) => roles.includes(r))
  })
  return (allowed?.path as string) ?? '/login'
}

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
      next({ path: firstAllowedPath() })
      return
    }
  }
  next()
})

router.afterEach((to) => {
  document.title = (to.meta.title ? `${to.meta.title} - ` : '') + '学生奶订购系统'
})

export default router
