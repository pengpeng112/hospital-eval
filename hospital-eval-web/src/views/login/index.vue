<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const store = useUserStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function onLogin() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await store.login(form)
    ElMessage.success('登录成功')
    if (data.mustChangePwd || store.mustChangePwd) {
      router.replace('/change-password')
      return
    }
    const redirect = route.query && route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/workbench')
  } catch (e) {
    // 错误提示已由 request 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 class="login-title">医院等级评审与质控指标数据集成系统</h2>
      <el-form :model="form" label-width="0" @keyup.enter="onLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" autocomplete="username">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password autocomplete="current-password">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="onLogin">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card {
  width: 380px;
  padding: 8px 8px 24px;
}
.login-title {
  font-size: 17px;
  text-align: center;
  margin: 8px 0 24px;
  color: #303133;
}
</style>
