<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { roleOf } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const collapsed = ref(false)

/**
 * 左侧固定 7 项菜单（04 §13，顺序不可变），按当前角色过滤。
 * "结果汇总""大屏展示"为二级菜单。
 */
const menus = [
  { path: '/workbench', title: '工作台', roles: [] },
  { path: '/metric', title: '指标维护', roles: ['ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'] },
  { path: '/algorithm', title: '算法管理', roles: ['ADMIN', 'EXPERT'] },
  { path: '/department', title: '部门管理', roles: ['ADMIN', 'FUNC_LEADER'] },
  { path: '/manual', title: '人工上报', roles: ['ADMIN', 'LIAISON', 'AUDITOR', 'FUNC_LEADER'] },
  {
    title: '结果汇总',
    roles: [],
    children: [
      { path: '/summary/current', title: '当期版本', roles: [] },
      { path: '/summary/history', title: '历史版本', roles: [] }
    ]
  },
  {
    title: '大屏展示',
    roles: ['ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'],
    children: [
      { path: '/screen/dashboard', title: '监测大屏', roles: ['ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'] },
      { path: '/screen/reconcile', title: '省平台对账', roles: ['ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'] }
    ]
  }
]

const visibleMenus = computed(() =>
  menus
    .filter((m) => m.roles.length === 0 || m.roles.some((r) => store.hasRole(r)))
    .map((m) => ({
      ...m,
      children: m.children
        ? m.children.filter((c) => c.roles.length === 0 || c.roles.some((r) => store.hasRole(r)))
        : undefined
    }))
    .filter((m) => !m.children || m.children.length > 0)
)

const activePath = computed(() => route.path)
const breadcrumb = computed(() => route.meta && route.meta.title)

const isAdmin = computed(() => store.hasRole('ADMIN'))

async function onLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  } catch (e) {
    return
  }
  await store.logout()
  router.replace('/login')
}

function onUserCommand(command) {
  if (command === 'change-password') {
    router.push('/change-password')
  } else if (command === 'users') {
    router.push('/system/users')
  } else if (command === 'logout') {
    onLogout()
  }
}
</script>

<template>
  <el-container class="eval-layout">
    <el-aside :width="collapsed ? '64px' : '200px'">
      <div class="logo-bar">
        <span v-if="!collapsed">评审数据集成系统</span>
      </div>
      <el-menu
        :default-active="activePath"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="eval-menu"
      >
        <template v-for="menu in visibleMenus" :key="menu.title">
          <el-sub-menu v-if="menu.children" :index="menu.title">
            <template #title>{{ menu.title }}</template>
            <el-menu-item v-for="child in menu.children" :key="child.path" :index="child.path">
              {{ child.title }}
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="menu.path">{{ menu.title }}</el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="eval-header" height="56px">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapsed = !collapsed">
            <Fold v-if="!collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="sys-name">医院等级评审与质控指标数据集成系统</span>
        </div>
        <div class="header-right">
          <el-dropdown trigger="click" @command="onUserCommand">
            <span class="user-info">
              {{ store.user && store.user.realName }}
              <span class="user-dept">{{ store.user && store.user.deptName }}</span>
              <span class="user-roles">{{ store.roles.map(roleOf).join(' / ') }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="change-password">修改密码</el-dropdown-item>
                <el-dropdown-item v-if="isAdmin" command="users">用户管理</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="eval-main">
        <div class="eval-breadcrumb">{{ breadcrumb }}</div>
        <div class="eval-content">
          <router-view />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.eval-layout {
  height: 100vh;
}
.eval-menu {
  border-right: none;
  height: calc(100vh - 48px);
}
.logo-bar {
  height: 48px;
  line-height: 48px;
  text-align: center;
  color: #fff;
  background: #1f3a5f;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
}
.el-aside {
  background: #fff;
  border-right: 1px solid #e6e8eb;
  transition: none;
}
.eval-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e6e8eb;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.sys-name {
  font-weight: bold;
  color: #1f3a5f;
}
.collapse-btn {
  cursor: pointer;
  font-size: 18px;
}
.user-info {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #303133;
}
.user-dept,
.user-roles {
  color: #909399;
  font-size: 12px;
}
.eval-main {
  padding: 0;
  display: flex;
  flex-direction: column;
}
.eval-breadcrumb {
  padding: 12px 16px 0;
  color: #606266;
  font-size: 13px;
}
.eval-content {
  padding: 12px 16px 16px;
  flex: 1;
  overflow: auto;
}
</style>
