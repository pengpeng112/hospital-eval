<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listUsers, createUser, updateUser, resetPassword, listDepts } from '@/api/system'
import { ROLE, roleOf } from '@/utils/dict'

// 查询条件
const query = reactive({ page: 1, size: 20, keyword: '', roleCode: '' })
const total = ref(0)
const list = ref([])
const loading = ref(false)
const deptOptions = ref([])
const roleOptions = Object.keys(ROLE)

async function loadList() {
  loading.value = true
  try {
    const params = {
      page: query.page,
      size: query.size
    }
    if (query.keyword) params.keyword = query.keyword
    if (query.roleCode) params.roleCode = query.roleCode
    const data = await listUsers(params)
    list.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

async function loadDepts() {
  deptOptions.value = (await listDepts()) || []
}

function onSearch() {
  query.page = 1
  loadList()
}

// 新增 / 编辑
const dialog = reactive({ visible: false, mode: 'create', id: null })
const form = reactive({ username: '', realName: '', deptId: null, roleCodes: [], status: 1 })

function openCreate() {
  dialog.mode = 'create'
  dialog.id = null
  form.username = ''
  form.realName = ''
  form.deptId = null
  form.roleCodes = []
  form.status = 1
  dialog.visible = true
}

function openEdit(row) {
  dialog.mode = 'edit'
  dialog.id = row.id
  form.username = row.username
  form.realName = row.realName
  form.deptId = row.deptId
  form.roleCodes = (row.roles || []).slice()
  form.status = row.status
  dialog.visible = true
}

async function onSave() {
  if (dialog.mode === 'create') {
    if (!form.username || !form.realName || !form.deptId || form.roleCodes.length === 0) {
      ElMessage.warning('请填写用户名、姓名、科室并至少选择一个角色')
      return
    }
    await createUser({
      username: form.username,
      realName: form.realName,
      deptId: form.deptId,
      roleCodes: form.roleCodes
    })
    ElMessage.success('新增成功（初始密码为系统统一初始密码）')
  } else {
    if (!form.realName || !form.deptId || form.roleCodes.length === 0) {
      ElMessage.warning('请填写姓名、科室并至少选择一个角色')
      return
    }
    await updateUser(dialog.id, {
      realName: form.realName,
      deptId: form.deptId,
      roleCodes: form.roleCodes,
      status: form.status
    })
    ElMessage.success('保存成功')
  }
  dialog.visible = false
  loadList()
}

async function onResetPwd(row) {
  try {
    await ElMessageBox.confirm(
      `确认将用户「${row.realName || row.username}」的密码重置为系统初始密码？`,
      '重置密码',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  await resetPassword(row.id)
  ElMessage.success('已重置')
}

onMounted(() => {
  loadList()
  loadDepts()
})
</script>

<template>
  <div class="eval-card">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="用户名 / 姓名"
        clearable
        style="width: 220px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-select v-model="query.roleCode" placeholder="角色筛选" clearable style="width: 160px" @change="onSearch">
        <el-option v-for="(label, code) in ROLE" :key="code" :label="label" :value="code" />
      </el-select>
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button type="success" @click="openCreate">新增用户</el-button>
    </div>

    <el-table v-loading="loading" :data="list" size="small" border>
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column prop="realName" label="姓名" min-width="110" />
      <el-table-column prop="deptName" label="科室" min-width="160" />
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="r in row.roles || []" :key="r" size="small" class="role-tag">
            {{ roleOf(r) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" size="small" @click="onResetPwd(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="loadList"
      @size-change="onSearch"
    />

    <el-dialog v-model="dialog.visible" :title="dialog.mode === 'create' ? '新增用户' : '编辑用户'" width="480px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" :disabled="dialog.mode === 'edit'" placeholder="登录账号" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="科室">
          <el-select v-model="form.deptId" filterable style="width: 100%" placeholder="选择科室">
            <el-option v-for="d in deptOptions" :key="d.id" :label="d.deptName" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleCodes" multiple style="width: 100%" placeholder="多选">
            <el-option v-for="code in roleOptions" :key="code" :label="roleOf(code)" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dialog.mode === 'edit'" label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.role-tag {
  margin-right: 4px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
