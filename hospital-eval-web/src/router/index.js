import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import EvalLayout from '@/layout/EvalLayout.vue'

/**
 * 路由表严格按 04 §13 菜单表；另加 /login、/change-password、/system/users。
 * 左侧固定 7 项：工作台、指标维护、算法管理、部门管理、人工上报、结果汇总（二级）、大屏展示（二级）。
 */
const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/login/index.vue'), meta: { title: '登录' } },
  { path: '/change-password', name: 'changePassword', component: () => import('@/views/login/ChangePassword.vue'), meta: { title: '修改密码' } },
  {
    path: '/',
    component: EvalLayout,
    redirect: '/workbench',
    children: [
      { path: 'workbench', name: 'workbench', component: () => import('@/views/workbench/index.vue'), meta: { title: '工作台', roles: [] } },
      { path: 'metric', name: 'metric', component: () => import('@/views/metric/index.vue'), meta: { title: '指标维护', roles: ['ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'] } },
      { path: 'algorithm', name: 'algorithm', component: () => import('@/views/algorithm/index.vue'), meta: { title: '算法管理', roles: ['ADMIN', 'EXPERT'] } },
      { path: 'department', name: 'department', component: () => import('@/views/department/index.vue'), meta: { title: '部门管理', roles: ['ADMIN', 'FUNC_LEADER'] } },
      { path: 'manual', name: 'manual', component: () => import('@/views/manual/index.vue'), meta: { title: '人工上报', roles: ['ADMIN', 'LIAISON', 'AUDITOR', 'FUNC_LEADER'] } },
      { path: 'summary/current', name: 'summaryCurrent', component: () => import('@/views/summary/index.vue'), meta: { title: '结果汇总-当期版本', roles: [], mode: 'current' } },
      { path: 'summary/history', name: 'summaryHistory', component: () => import('@/views/summary/index.vue'), meta: { title: '结果汇总-历史版本', roles: [], mode: 'history' } },
      { path: 'screen/dashboard', name: 'screenDashboard', component: () => import('@/views/screen/dashboard.vue'), meta: { title: '监测大屏', roles: ['ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'] } },
      { path: 'screen/reconcile', name: 'screenReconcile', component: () => import('@/views/screen/reconcile.vue'), meta: { title: '省平台对账', roles: ['ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'] } },
      { path: 'system/users', name: 'systemUsers', component: () => import('@/views/system/users.vue'), meta: { title: '用户管理', roles: ['ADMIN'] } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/workbench' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const store = useUserStore()
  if (to.path === '/login') {
    return true
  }
  if (!store.token) {
    return { path: '/login', query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (!store.user) {
    try {
      await store.fetchMe()
    } catch (e) {
      return '/login'
    }
  }
  // 强制改密：只能进 /change-password
  if (store.mustChangePwd && to.path !== '/change-password') {
    return '/change-password'
  }
  // 角色过滤：meta.roles 为空 = 全部可见
  if (to.meta && Array.isArray(to.meta.roles) && to.meta.roles.length > 0) {
    if (!to.meta.roles.some((r) => store.hasRole(r))) {
      ElMessage.error('无权限访问')
      return '/workbench'
    }
  }
  return true
})

export default router
