<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { funcDeptSummary, listScopes, updateScope, exportScopes, importScopes, syncDeptCodes } from '@/api/metric'
import { useUserStore } from '@/stores/user'
import { EXTRACT_MODE } from '@/utils/dict'
import ExtractModeTag from '@/components/ExtractModeTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import DeptSelect from '@/components/DeptSelect.vue'

const store = useUserStore()
const canEdit = computed(() => store.hasRole('ADMIN', 'FUNC_LEADER'))
const isAdmin = computed(() => store.hasRole('ADMIN'))

// ---------------- 上部：归口部门卡片 ----------------
const funcCards = ref([])
const cardLoading = ref(false)

async function loadFuncCards() {
  cardLoading.value = true
  try {
    funcCards.value = (await funcDeptSummary()) || []
  } finally {
    cardLoading.value = false
  }
}

// ---------------- 下部：一标多科实例表 ----------------
const query = reactive({ page: 1, size: 20, keyword: '', deptId: null, skipFlag: '' })
const total = ref(0)
const list = ref([])
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.keyword) params.keyword = query.keyword
    if (query.deptId != null) params.deptId = query.deptId
    if (query.skipFlag !== '') params.skipFlag = query.skipFlag
    const data = await listScopes(params)
    list.value = data.list || []
    total.value = data.total || 0
    computeSpans()
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.page = 1
  loadList()
}

function onReset() {
  query.keyword = ''
  query.deptId = null
  query.skipFlag = ''
  onSearch()
}

// 按指标分组合并（同一 metricId 的多行合并指标编号/指标名称/专业三列）
const spanMap = reactive({})
function computeSpans() {
  Object.keys(spanMap).forEach((k) => delete spanMap[k])
  let prevId = null
  let startIndex = -1
  for (let i = 0; i < list.value.length; i++) {
    const metricId = list.value[i].metricId
    if (metricId !== prevId) {
      if (startIndex >= 0) {
        spanMap[startIndex] = i - startIndex
      }
      prevId = metricId
      startIndex = i
    }
  }
  if (startIndex >= 0) {
    spanMap[startIndex] = list.value.length - startIndex
  }
}

function arraySpanMethod({ rowIndex, columnIndex }) {
  // 合并列：0 指标编号、1 指标名称、2 专业
  if (columnIndex > 2) return
  const span = spanMap[rowIndex]
  if (span != null) {
    return { rowspan: span, colspan: 1 }
  }
  return { rowspan: 0, colspan: 0 }
}

// 科室范围标签组：超过 3 个折叠为 +N，悬停显示全部
function scopeTagsOf(row) {
  return Array.isArray(row.hisDeptNames) ? row.hisDeptNames : []
}

// ---------------- 工具栏：导出 / 导入 / 同步 ----------------
const exporting = ref(false)
async function onExport() {
  exporting.value = true
  try {
    const params = {}
    if (query.keyword) params.keyword = query.keyword
    if (query.deptId != null) params.deptId = query.deptId
    if (query.skipFlag !== '') params.skipFlag = query.skipFlag
    await exportScopes(params)
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
}

const importResult = reactive({ visible: false, updated: 0, errors: [] })
async function onImportUpload(option) {
  try {
    const data = await importScopes(option.file)
    importResult.updated = data.updated || 0
    importResult.errors = data.errors || []
    importResult.visible = true
    loadList()
    loadFuncCards()
  } catch (e) {
    // 错误已由拦截器弹出
  }
  return Promise.resolve()
}

const syncResult = reactive({ visible: false, updated: 0, unmatchedNames: [] })
async function onSyncDeptCodes() {
  try {
    await ElMessageBox.confirm(
      '将从 HIS 只读库按科室名精确匹配并回写 his_dept_codes，确认执行？',
      '同步 HIS 科室编码',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  const data = await syncDeptCodes()
  syncResult.updated = data.updated || 0
  syncResult.unmatchedNames = data.unmatchedNames || []
  syncResult.visible = true
  loadList()
}

// ---------------- 编辑弹窗 ----------------
const dialog = reactive({ visible: false, scopeId: null, saving: false })
const form = reactive({
  hisDeptNames: [],
  liaisonUserId: null,
  auditorUserId: null,
  funcLeaderUserId: null,
  extractModeOverride: '',
  skipFlag: false,
  skipReason: ''
})
// 编辑期间记住原行，用于 UserSelect 回显姓名
const editRow = ref(null)

const OVERRIDE_OPTIONS = [
  { value: '', label: '继承指标' },
  { value: 'SYS', label: '系统提取' },
  { value: 'MANUAL', label: '人工填报' },
  { value: 'HYBRID', label: '系统+人工' },
  { value: 'PROV', label: '省级提取' }
]

function openEdit(row) {
  if (!canEdit.value) return
  editRow.value = row
  dialog.scopeId = row.id
  form.hisDeptNames = scopeTagsOf(row).slice()
  // /api/scope/list 不返回联络员/内审员/终审人的 userId（只有姓名），
  // 故三个下拉初始为空、界面显示"当前"姓名提示；不改则保存时传 null，后端稀疏更新不覆盖
  form.liaisonUserId = null
  form.auditorUserId = null
  form.funcLeaderUserId = null
  form.extractModeOverride = row.extractModeOverride || ''
  form.skipFlag = !!row.skipFlag
  form.skipReason = row.skipReason || ''
  dialog.visible = true
}

async function onSaveScope() {
  if (form.skipFlag && !form.skipReason.trim()) {
    ElMessage.warning('免统时必须填写免统原因')
    return
  }
  dialog.saving = true
  try {
    await updateScope(dialog.scopeId, {
      hisDeptNames: form.hisDeptNames,
      liaisonUserId: form.liaisonUserId,
      auditorUserId: form.auditorUserId,
      funcLeaderUserId: form.funcLeaderUserId,
      extractModeOverride: form.extractModeOverride === '' ? null : form.extractModeOverride,
      skipFlag: form.skipFlag,
      skipReason: form.skipFlag ? form.skipReason.trim() : null
    })
    ElMessage.success('保存成功')
    dialog.visible = false
    loadList()
  } finally {
    dialog.saving = false
  }
}

onMounted(() => {
  loadFuncCards()
  loadList()
})
</script>

<template>
  <div class="department-page">
    <!-- 上部：归口部门卡片 -->
    <div v-loading="cardLoading" class="func-cards eval-card">
      <h4 class="block-title">归口部门统计</h4>
      <div class="card-list">
        <div v-for="card in funcCards" :key="card.funcDeptId ?? 'unassigned'" class="func-card">
          <div class="func-card-name">{{ card.funcDeptName || '未分配' }}</div>
          <div class="func-card-nums">
            <span>指标数 {{ card.metricCount }}</span>
            <span>实例数 {{ card.scopeCount }}</span>
          </div>
        </div>
        <el-empty v-if="!cardLoading && funcCards.length === 0" description="暂无数据" :image-size="48" />
      </div>
      <div class="func-tip">按归口部门分配指标请到【指标维护】修改</div>
    </div>

    <!-- 下部：一标多科实例表 -->
    <div class="scope-panel eval-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="指标编号 / 名称 / 科室"
          clearable
          size="small"
          style="width: 220px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <DeptSelect v-model="query.deptId" placeholder="责任科室" style="width: 180px" @update:model-value="onSearch" />
        <el-select v-model="query.skipFlag" placeholder="是否免统" clearable size="small" style="width: 120px" @change="onSearch">
          <el-option label="免统" :value="true" />
          <el-option label="不免统" :value="false" />
        </el-select>
        <el-button size="small" @click="onReset">重置</el-button>
        <div class="toolbar-right">
          <el-button size="small" type="primary" :loading="exporting" @click="onExport">导出 Excel</el-button>
          <el-upload
            :show-file-list="false"
            accept=".xlsx"
            :http-request="onImportUpload"
          >
            <el-button size="small" type="success" :disabled="!canEdit">导入 Excel</el-button>
          </el-upload>
          <el-button v-if="isAdmin" size="small" type="warning" @click="onSyncDeptCodes">同步 HIS 科室编码</el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        size="small"
        border
        :span-method="arraySpanMethod"
      >
        <el-table-column prop="metricCode" label="指标编号" min-width="130" show-overflow-tooltip />
        <el-table-column prop="metricName" label="指标名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="specialtyName" label="专业" width="110" show-overflow-tooltip />
        <el-table-column prop="respDeptName" label="责任科室" min-width="130" />
        <el-table-column label="科室范围" min-width="220">
          <template #default="{ row }">
            <template v-if="scopeTagsOf(row).length > 0">
              <el-tag
                v-for="name in scopeTagsOf(row).slice(0, 3)"
                :key="name"
                size="small"
                class="scope-tag"
                disable-transitions
              >{{ name }}</el-tag>
              <el-tooltip
                v-if="scopeTagsOf(row).length > 3"
                :content="scopeTagsOf(row).join('、')"
                placement="top"
              >
                <el-tag size="small" type="info" class="scope-tag" disable-transitions>
                  +{{ scopeTagsOf(row).length - 3 }}
                </el-tag>
              </el-tooltip>
            </template>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="liaisonName" label="联络员" width="90" />
        <el-table-column prop="auditorName" label="内审员" width="90" />
        <el-table-column prop="funcLeaderName" label="终审人" width="90" />
        <el-table-column label="提取方式覆盖" width="110">
          <template #default="{ row }">
            <ExtractModeTag v-if="row.extractModeOverride" :mode="row.extractModeOverride" />
            <span v-else class="muted">继承</span>
          </template>
        </el-table-column>
        <el-table-column label="免统" width="80">
          <template #default="{ row }">
            <el-tooltip v-if="row.skipFlag" :content="row.skipReason || '免统'" placement="top">
              <el-tag type="danger" size="small" disable-transitions>免统</el-tag>
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        class="pager"
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        @current-change="loadList"
      />
    </div>

    <!-- 导入结果弹窗 -->
    <el-dialog v-model="importResult.visible" title="导入结果" width="560px">
      <p>更新 <b>{{ importResult.updated }}</b> 条实例</p>
      <template v-if="importResult.errors.length > 0">
        <p class="err-title">错误行（{{ importResult.errors.length }}）：</p>
        <el-table :data="importResult.errors" size="small" border max-height="260">
          <el-table-column prop="row" label="行号" width="80" />
          <el-table-column prop="message" label="错误原因" min-width="300" />
        </el-table>
      </template>
      <template #footer>
        <el-button type="primary" @click="importResult.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 同步结果弹窗 -->
    <el-dialog v-model="syncResult.visible" title="同步 HIS 科室编码结果" width="560px">
      <p>已更新 <b>{{ syncResult.updated }}</b> 条实例的 his_dept_codes</p>
      <template v-if="syncResult.unmatchedNames.length > 0">
        <p class="err-title">未匹配科室名（{{ syncResult.unmatchedNames.length }}）：</p>
        <div class="unmatched">
          <el-tag v-for="name in syncResult.unmatchedNames" :key="name" size="small" type="danger" class="scope-tag" disable-transitions>
            {{ name }}
          </el-tag>
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="syncResult.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="dialog.visible" title="编辑科室实例" width="620px">
      <el-form :model="form" label-width="110px" size="small">
        <el-form-item label="科室范围">
          <el-select
            v-model="form.hisDeptNames"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入 HIS 科室名"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="联络员">
          <UserSelect
            v-model="form.liaisonUserId"
            :initial-label="editRow && editRow.liaisonName ? editRow.liaisonName : ''"
            placeholder="搜索并选择联络员"
          />
          <span v-if="editRow && editRow.liaisonName" class="current-hint">当前：{{ editRow.liaisonName }}</span>
        </el-form-item>
        <el-form-item label="内审员">
          <UserSelect
            v-model="form.auditorUserId"
            :initial-label="editRow && editRow.auditorName ? editRow.auditorName : ''"
            placeholder="搜索并选择内审员"
          />
          <span v-if="editRow && editRow.auditorName" class="current-hint">当前：{{ editRow.auditorName }}</span>
        </el-form-item>
        <el-form-item label="终审人">
          <UserSelect
            v-model="form.funcLeaderUserId"
            :initial-label="editRow && editRow.funcLeaderName ? editRow.funcLeaderName : ''"
            placeholder="搜索并选择终审人"
          />
          <span v-if="editRow && editRow.funcLeaderName" class="current-hint">当前：{{ editRow.funcLeaderName }}</span>
        </el-form-item>
        <el-form-item label="提取方式覆盖">
          <el-select v-model="form.extractModeOverride" style="width: 220px">
            <el-option v-for="opt in OVERRIDE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否免统">
          <el-switch v-model="form.skipFlag" active-text="免统" inactive-text="参与统计" />
          <el-input
            v-if="form.skipFlag"
            v-model="form.skipReason"
            class="skip-reason-input"
            placeholder="免统原因（必填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="onSaveScope">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.department-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.block-title {
  margin: 0 0 10px;
  color: #1f3a5f;
}
.card-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.func-card {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 10px 16px;
  min-width: 160px;
  cursor: default;
  background: #fafbfc;
}
.func-card-name {
  font-weight: bold;
  margin-bottom: 4px;
}
.func-card-nums {
  display: flex;
  gap: 12px;
  color: #606266;
  font-size: 12px;
}
.func-tip {
  margin-top: 10px;
  color: #909399;
  font-size: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
}
.scope-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}
.skip-reason-input {
  margin-left: 12px;
  width: 240px;
}
.current-hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.muted {
  color: #909399;
}
.unmatched {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.err-title {
  color: #c0392b;
}
</style>
