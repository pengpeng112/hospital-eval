<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const store = useUserStore()

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false)

/** 前端校验：≥ 8 位且同时含字母和数字（与后端 04 §12.1 规则一致）。 */
function validNewPwd(pwd) {
  return typeof pwd === 'string' && pwd.length >= 8 && /[A-Za-z]/.test(pwd) && /\d/.test(pwd)
}

async function onChange() {
  if (!form.oldPassword || !form.newPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  if (!validNewPwd(form.newPassword)) {
    ElMessage.warning('新密码至少 8 位，且同时包含字母和数字')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  loading.value = true
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success('密码修改成功，请重新登录')
    store.clearMustChangePwd()
    await store.logout()
    router.replace('/login')
  } catch (e) {
    // 错误提示已由 request 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="pwd-wrap">
    <el-card class="pwd-card">
      <h2 class="pwd-title">修改密码</h2>
      <el-form :model="form" label-width="90px" @keyup.enter="onChange">
        <el-form-item label="旧密码">
          <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="≥8位，含字母和数字" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="onChange">确 定</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.pwd-wrap {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.pwd-card {
  width: 420px;
  padding: 8px 8px 24px;
}
.pwd-title {
  font-size: 17px;
  text-align: center;
  margin: 8px 0 24px;
}
</style>
