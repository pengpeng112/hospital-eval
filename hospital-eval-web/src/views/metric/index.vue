<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getMetricTree, listMetrics, getMetric, updateMetric } from '@/api/metric'
import { listOperLogs } from '@/api/system'
import { useUserStore } from '@/stores/user'
import { AGG_MODE } from '@/utils/dict'
import ExtractModeTag from '@/components/ExtractModeTag.vue'
import DeptSelect from '@/components/DeptSelect.vue'

const store = useUserStore()
const canEdit = computed(() => store.hasRole('ADMIN', 'FUNC_LEADER'))

// ---------------- 左栏章节树 ----------------
const treeData = ref([])
const treeSearch = ref('')
const treeRef = ref(null)
const currentChapter = ref(null)

function nodeLabelOf(node) {
  return `${node.name}（${node.metricCount ?? 0}）`
}

function filterNode(value, data) {
  if (!value) return true
  const kw = value.toLowerCase()
  return (data.name && data.name.toLowerCase().includes(kw))
    || (data.code && data.code.toLowerCase().includes(kw))
}

watch(treeSearch, (val) => {
  treeRef.value && treeRef.value.filter(val)
})

function onTreeNodeClick(nodeData) {
  currentChapter.value = nodeData.code
  query.chapterCode = nodeData.code
  query.page = 1
  loadList()
}

async function loadTree() {
  treeData.value = (await getMetricTree()) || []
}

// ---------------- 右栏列表 ----------------
const query = reactive({
  page: 1,
  size: 20,
  chapterCode: '',
  keyword: '',
  extractMode: '',
  applicable: ''
})
const total = ref(0)
const list = ref([])
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.chapterCode) params.chapterCode = query.chapterCode
    if (query.keyword) params.keyword = query.keyword
    if (query.extractMode) params.extractMode = query.extractMode
    if (query.applicable !== '') params.applicable = query.applicable
    const data = await listMetrics(params)
    list.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.page = 1
  loadList()
}

function onReset() {
  query.chapterCode = ''
  currentChapter.value = null
  query.keyword = ''
  query.extractMode = ''
  query.applicable = ''
  onSearch()
}

function aggModeLabel(mode) {
  return AGG_MODE[mode] || mode || '-'
}

// ---------------- 抽屉 ----------------
const drawer = reactive({ visible: false, loading: false, metricId: null })
const detail = ref(null) // {dict, atoms, scopes}
const logs = ref([])
const logsLoading = ref(false)

const form = reactive({
  hospCaliberDesc: '',
  isApplicable: true,
  inapplicableReason: '',
  extractMode: '',
  aggMode: '',
  targetValue: null,
  ownerFuncDeptId: null,
  changeReason: ''
})

async function openDrawer(row) {
  drawer.metricId = row.id
  drawer.visible = true
  drawer.loading = true
  form.changeReason = ''
  try {
    detail.value = await getMetric(row.id)
    const d = detail.value.dict
    form.hospCaliberDesc = d.hospCaliberDesc || ''
    form.isApplicable = d.isApplicable !== false
    form.inapplicableReason = d.inapplicableReason || ''
    form.extractMode = d.extractMode || ''
    form.aggMode = d.aggMode || ''
    form.targetValue = d.targetValue == null ? null : Number(d.targetValue)
    form.ownerFuncDeptId = d.ownerFuncDeptId == null ? null : d.ownerFuncDeptId
  } finally {
    drawer.loading = false
  }
  loadLogs()
}

/** 修改记录：变化字段从 detailJson 的 before/after 键位解析（04 §12.1 sys_oper_log）。 */
function changeFieldsOf(logRow) {
  if (!logRow.detailJson) return '-'
  try {
    const detail = JSON.parse(logRow.detailJson)
    const before = detail.before || {}
    const after = detail.after || {}
    const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])]
    if (keys.length === 0) return logRow.action || '-'
    return keys.join('、')
  } catch (e) {
    return logRow.action || '-'
  }
}

function reasonOf(logRow) {
  if (!logRow.detailJson) return ''
  try {
    const detail = JSON.parse(logRow.detailJson)
    return detail.reason || ''
  } catch (e) {
    return ''
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const data = await listOperLogs({
      page: 1,
      size: 20,
      module: 'METRIC',
      targetType: 'eval_metric_dict',
      targetId: String(drawer.metricId)
    })
    logs.value = data.list || []
  } finally {
    logsLoading.value = false
  }
}

async function onSave() {
  if (!canEdit.value) return
  if (!form.changeReason || !form.changeReason.trim()) {
    ElMessage.warning('修改原因必填')
    return
  }
  if (!form.isApplicable && !form.inapplicableReason.trim()) {
    ElMessage.warning('不适用时须填写不适用原因')
    return
  }
  await updateMetric(drawer.metricId, {
    hospCaliberDesc: form.hospCaliberDesc,
    isApplicable: form.isApplicable,
    inapplicableReason: form.isApplicable ? null : form.inapplicableReason,
    extractMode: form.extractMode,
    aggMode: form.aggMode,
    targetValue: form.targetValue,
    ownerFuncDeptId: form.ownerFuncDeptId,
    changeReason: form.changeReason.trim()
  })
  ElMessage.success('保存成功')
  drawer.visible = false
  loadList()
  loadTree()
}

onMounted(() => {
  loadTree()
  loadList()
})
</script>

<template>
  <div class="metric-page">
    <!-- 左栏：章节树 -->
    <div class="left-panel eval-card">
      <el-input v-model="treeSearch" placeholder="搜索章节名称/编号" clearable size="small" />
      <el-tree
        ref="treeRef"
        class="chapter-tree"
        :data="treeData"
        :props="{ children: 'children', label: 'name' }"
        node-key="code"
        :filter-node-method="filterNode"
        highlight-current
        @node-click="onTreeNodeClick"
      >
        <template #default="{ data }">
          <span class="tree-node" :class="{ active: currentChapter === data.code }">
            {{ nodeLabelOf(data) }}
          </span>
        </template>
      </el-tree>
    </div>

    <!-- 右栏：查询 + 表格 -->
    <div class="right-panel eval-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="编号 / 代号 / 名称"
          clearable
          size="small"
          style="width: 200px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-select v-model="query.extractMode" placeholder="提取方式" clearable size="small" style="width: 140px" @change="onSearch">
          <el-option label="系统提取" value="SYS" />
          <el-option label="人工填报" value="MANUAL" />
          <el-option label="系统+人工" value="HYBRID" />
          <el-option label="省级提取" value="PROV" />
        </el-select>
        <el-select v-model="query.applicable" placeholder="是否适用" clearable size="small" style="width: 120px" @change="onSearch">
          <el-option label="适用" :value="true" />
          <el-option label="不适用" :value="false" />
        </el-select>
        <el-button size="small" @click="onReset">重置</el-button>
        <span v-if="currentChapter" class="chapter-hint">当前章节：{{ currentChapter }}</span>
      </div>

      <el-table v-loading="loading" :data="list" size="small" border>
        <el-table-column prop="metricCode" label="指标编号" min-width="130" show-overflow-tooltip />
        <el-table-column prop="aliasCode" label="专科代号" min-width="100" show-overflow-tooltip />
        <el-table-column prop="metricName" label="指标名称" min-width="220">
          <template #default="{ row }">
            <span class="metric-name">{{ row.metricName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="metricAttr" label="属性" width="70" />
        <el-table-column prop="unit" label="单位" width="80" show-overflow-tooltip />
        <el-table-column label="提取方式" width="100">
          <template #default="{ row }">
            <ExtractModeTag :mode="row.extractMode" :applicable="row.isApplicable" />
          </template>
        </el-table-column>
        <el-table-column label="汇总方式" width="90">
          <template #default="{ row }">{{ aggModeLabel(row.aggMode) }}</template>
        </el-table-column>
        <el-table-column label="分子/分母原子编号" width="150">
          <template #default="{ row }">
            <div class="atom-cell">
              <div>{{ row.numAtomCode || '-' }}</div>
              <div>{{ row.denAtomCode || '-' }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="scopeCount" label="科室数" width="70" align="center" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDrawer(row)">
              {{ canEdit ? '编辑' : '查看' }}
            </el-button>
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

    <!-- 查看 / 编辑抽屉 -->
    <el-drawer v-model="drawer.visible" size="60%" :title="canEdit ? '编辑指标' : '查看指标'">
      <div v-loading="drawer.loading" class="drawer-body">
        <template v-if="detail">
          <!-- 只读区 -->
          <h4 class="section-title">字典信息</h4>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="总表编号">{{ detail.dict.metricCode }}</el-descriptions-item>
            <el-descriptions-item label="专科代号">{{ detail.dict.aliasCode || '-' }}</el-descriptions-item>
            <el-descriptions-item label="章节路径" :span="2">{{ detail.dict.chapterPath || '-' }}</el-descriptions-item>
            <el-descriptions-item label="国家计算方法" :span="2">{{ detail.dict.stdFormulaDesc || '-' }}</el-descriptions-item>
            <el-descriptions-item label="业务系统来源" :span="2">{{ detail.dict.dataSysSource || '-' }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="section-title">原子指标</h4>
          <el-table :data="detail.atoms || []" size="small" border max-height="220">
            <el-table-column prop="code" label="编号" min-width="140" />
            <el-table-column prop="name" label="名称" min-width="240" show-overflow-tooltip />
            <el-table-column prop="atomRole" label="角色" width="100" />
          </el-table>

          <h4 class="section-title">科室实例</h4>
          <el-table :data="detail.scopes || []" size="small" border max-height="220">
            <el-table-column prop="respDeptName" label="科室名" min-width="160" />
            <el-table-column label="是否免统" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.skipFlag" type="danger" size="small" disable-transitions>免统</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="提取方式覆盖" width="120">
              <template #default="{ row }">
                <ExtractModeTag v-if="row.extractModeOverride" :mode="row.extractModeOverride" />
                <span v-else class="muted">继承指标</span>
              </template>
            </el-table-column>
          </el-table>

          <!-- 可编辑区：仅 ADMIN / FUNC_LEADER -->
          <template v-if="canEdit">
            <h4 class="section-title">编辑</h4>
            <el-form :model="form" label-width="120px" size="small">
              <el-form-item label="本院统计口径">
                <el-input v-model="form.hospCaliberDesc" type="textarea" :rows="3" placeholder="本院口径说明" />
              </el-form-item>
              <el-form-item label="是否适用">
                <el-switch v-model="form.isApplicable" active-text="适用" inactive-text="不适用" />
                <el-input
                  v-if="!form.isApplicable"
                  v-model="form.inapplicableReason"
                  class="inapplicable-input"
                  placeholder="不适用原因（必填）"
                />
              </el-form-item>
              <el-form-item label="提取方式">
                <el-select v-model="form.extractMode" style="width: 200px">
                  <el-option label="系统提取" value="SYS" />
                  <el-option label="人工填报" value="MANUAL" />
                  <el-option label="系统+人工" value="HYBRID" />
                  <el-option label="省级提取" value="PROV" />
                </el-select>
              </el-form-item>
              <el-form-item label="汇总方式">
                <el-select v-model="form.aggMode" style="width: 200px">
                  <el-option label="明细去重" value="DEDUP_DTL" />
                  <el-option label="分科累加" value="DEPT_SUM" />
                </el-select>
              </el-form-item>
              <el-form-item label="目标值">
                <el-input-number v-model="form.targetValue" :precision="4" :controls="false" style="width: 200px" />
              </el-form-item>
              <el-form-item label="归口职能部门">
                <DeptSelect v-model="form.ownerFuncDeptId" dept-type="FUNC" style="width: 260px" />
              </el-form-item>
              <el-form-item label="修改原因">
                <el-input v-model="form.changeReason" placeholder="必填，写入操作日志" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="onSave">保存</el-button>
              </el-form-item>
            </el-form>
          </template>

          <!-- 修改记录 -->
          <h4 class="section-title">最近修改记录</h4>
          <el-table v-loading="logsLoading" :data="logs" size="small" border>
            <el-table-column prop="createTime" label="时间" width="160" />
            <el-table-column prop="username" label="操作人" width="110" />
            <el-table-column label="变化字段" min-width="160">
              <template #default="{ row }">{{ changeFieldsOf(row) }}</template>
            </el-table-column>
            <el-table-column label="原因" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ reasonOf(row) || '-' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.metric-page {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.left-panel {
  width: 320px;
  flex-shrink: 0;
}
.chapter-tree {
  margin-top: 8px;
  height: calc(100vh - 240px);
  overflow: auto;
}
.tree-node.active {
  color: #409eff;
  font-weight: bold;
}
.right-panel {
  flex: 1;
  min-width: 0;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.chapter-hint {
  color: #909399;
  font-size: 12px;
}
.metric-name {
  white-space: normal;
  word-break: break-all;
  line-height: 1.4;
}
.atom-cell div {
  line-height: 1.5;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.section-title {
  margin: 16px 0 8px;
  color: #1f3a5f;
}
.inapplicable-input {
  margin-left: 12px;
  width: 260px;
}
.muted {
  color: #909399;
}
.drawer-body {
  padding-bottom: 24px;
}
</style>
