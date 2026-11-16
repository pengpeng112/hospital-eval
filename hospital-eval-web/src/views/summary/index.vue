<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listSummary, listMetricScopes, listResultBatches, exportMaster } from '@/api/result'
import { getMetricTree } from '@/api/metric'
import { useUserStore } from '@/stores/user'
import ExtractModeTag from '@/components/ExtractModeTag.vue'
import AuditStatusTag from '@/components/AuditStatusTag.vue'
import ExpertVerifyDrawer from './ExpertVerifyDrawer.vue'
import PatientDetailModal from './PatientDetailModal.vue'

/**
 * 结果汇总主页面（TASK-15 步骤 2）：当期/历史双模式（route.meta.mode），
 * 全院汇总 → 行展开科室分布 → 蓝色数字点患者明细 → 住院号点病历抽屉（四级下钻）。
 */
const route = useRoute()
const store = useUserStore()
const mode = computed(() => (route.meta && route.meta.mode) || 'current')
const canExport = computed(() => store.hasRole('ADMIN', 'FUNC_LEADER', 'EXPERT', 'LEADER'))

// ---------------- 筛选条件 ----------------
const filters = reactive({
  chapterCode: '',
  specialty: '',
  extractMode: '',
  zeroOnly: false,
  keyword: '',
  years: ['2022', '2023', '2024', '2025']
})
const treeData = ref([])
const treeProps = { value: 'code', label: 'name', children: 'children' }

// ---------------- 历史模式 ----------------
const batches = ref([])
const batchId = ref(null)
const compareWithCurrent = ref(false)
const currentCompare = reactive({ map: {} }) // metricId -> year -> val（当期值，用于对比标橙）

async function loadBatches() {
  if (mode.value !== 'history') return
  const data = await listResultBatches()
  batches.value = Array.isArray(data) ? data : (data.list || [])
  // 批次下拉显示"批次号 · 类型 · 版本说明 · 时间"；必须选一个批次才查询
}

function batchLabelOf(b) {
  return [b.batchNo, b.triggerType, b.versionTag, b.startTime].filter(Boolean).join(' · ')
}

watch(batchId, () => {
  if (mode.value === 'history') {
    query.page = 1
    loadList()
  }
})

watch(compareWithCurrent, (v) => {
  if (v && mode.value === 'history') {
    loadCurrentCompare()
  }
})

async function loadCurrentCompare() {
  const params = buildParams()
  delete params.batchId
  const data = await listSummary(params)
  const map = {}
  for (const row of data.list || []) {
    map[row.metricId] = row.years || {}
  }
  currentCompare.map = map
}

// ---------------- 主表 ----------------
const query = reactive({ page: 1, size: 20 })
const list = ref([])
const total = ref(0)
const loading = ref(false)

function buildParams() {
  const params = { page: query.page, size: query.size, years: filters.years.join(',') }
  if (filters.chapterCode) params.chapterCode = filters.chapterCode
  if (filters.specialty) params.specialty = filters.specialty
  if (filters.extractMode) params.extractMode = filters.extractMode
  if (filters.zeroOnly) params.zeroOnly = true
  if (filters.keyword) params.keyword = filters.keyword
  if (mode.value === 'history' && batchId.value) params.batchId = batchId.value
  return params
}

async function loadList() {
  // 历史模式必须选批次
  if (mode.value === 'history' && !batchId.value) {
    list.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const data = await listSummary(buildParams())
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
  filters.chapterCode = ''
  filters.specialty = ''
  filters.extractMode = ''
  filters.zeroOnly = false
  filters.keyword = ''
  filters.years = ['2022', '2023', '2024', '2025']
  onSearch()
}

// URL 参数预填（04 §13）：大屏/工作台/对账页跳转靠它
watch(() => route.query, (q) => {
  if (!q) return
  let changed = false
  if (q.chapterCode !== undefined) { filters.chapterCode = String(q.chapterCode); changed = true }
  if (q.keyword !== undefined) { filters.keyword = String(q.keyword); changed = true }
  if (q.extractMode !== undefined) { filters.extractMode = String(q.extractMode); changed = true }
  if (q.zeroOnly !== undefined) { filters.zeroOnly = q.zeroOnly === 'true' || q.zeroOnly === true; changed = true }
  if (changed) {
    query.page = 1
    loadList()
  }
})

onMounted(async () => {
  // 先读 URL 参数再查询
  const q = route.query || {}
  if (q.chapterCode !== undefined) filters.chapterCode = String(q.chapterCode)
  if (q.keyword !== undefined) filters.keyword = String(q.keyword)
  if (q.extractMode !== undefined) filters.extractMode = String(q.extractMode)
  if (q.zeroOnly !== undefined) filters.zeroOnly = q.zeroOnly === 'true' || q.zeroOnly === true
  treeData.value = (await getMetricTree()) || []
  if (mode.value === 'history') await loadBatches()
  loadList()
})

// ---------------- 年份单元格 ----------------
function yearCellOf(row, year) {
  return (row.years && row.years[String(year)]) || null
}

function valTextOf(cell, row) {
  if (!cell) return '—'
  if (cell.val != null) {
    return row.unit === '%' ? `${cell.val}%` : String(cell.val)
  }
  return cell.qualitative || '—'
}

/** 与当期对比：历史模式下该年当期结果值与所选批次不同 → 标橙。 */
function isDiffFromCurrent(row, year) {
  if (mode.value !== 'history' || !compareWithCurrent.value) return false
  const cur = (currentCompare.map[row.metricId] || {})[String(year)]
  const cell = yearCellOf(row, year)
  if (!cur || !cell) return false
  const a = cur.val == null ? (cur.qualitative || '') : String(cur.val)
  const b = cell.val == null ? (cell.qualitative || '') : String(cell.val)
  return a !== b
}

function currentValText(row, year) {
  const cur = (currentCompare.map[row.metricId] || {})[String(year)]
  if (!cur) return null
  return cur.val == null ? (cur.qualitative || null) : String(cur.val)
}

function fmtNum(v) {
  return v == null ? null : String(v)
}

// ---------------- 展开行：科室分布 ----------------
const expandLoading = reactive({})
const expandMap = reactive({}) // metricId -> scopes 数据

async function onExpandChange(row, expandedRows) {
  const expanded = expandedRows.some((r) => r.metricId === row.metricId)
  if (!expanded) return
  expandLoading[row.metricId] = true
  try {
    const params = { years: filters.years.join(',') }
    if (mode.value === 'history' && batchId.value) params.batchId = batchId.value
    const data = await listMetricScopes(row.metricId, params)
    expandMap[row.metricId] = data.scopes || []
  } finally {
    expandLoading[row.metricId] = false
  }
}

// ---------------- 导出总表 ----------------
const exporting = ref(false)
async function onExportMaster() {
  exporting.value = true
  try {
    const params = { years: filters.years.join(',') }
    if (mode.value === 'history' && batchId.value) params.batchId = batchId.value
    await exportMaster(params)
  } finally {
    exporting.value = false
  }
}

// ---------------- 下钻 ----------------
const verify = reactive({ visible: false, metricId: null })
const detail = reactive({
  visible: false, metricId: null, scopeId: 0, year: '', role: 'NUM',
  batchId: null, metricName: '', deptLabel: '全院'
})

function openVerify(row) {
  verify.metricId = row.metricId
  verify.visible = true
}

function openDetail(row, year, role, scopeId = 0, deptLabel = '全院') {
  detail.metricId = row.metricId
  detail.metricName = row.metricName
  detail.scopeId = scopeId
  detail.year = String(year)
  detail.role = role
  detail.batchId = mode.value === 'history' && batchId.value ? batchId.value : null
  detail.deptLabel = deptLabel
  detail.visible = true
}

function onNumClick(row, year) {
  openDetail(row, year, 'NUM', 0, '全院')
}

function onDenClick(row, year) {
  openDetail(row, year, 'DEN', 0, '全院')
}

function onScopeNumClick(scopeRow, metricRow, year) {
  openDetail(metricRow, year, 'NUM', scopeRow.scopeId, scopeRow.deptName)
}

function onScopeDenClick(scopeRow, metricRow, year) {
  openDetail(metricRow, year, 'DEN', scopeRow.scopeId, scopeRow.deptName)
}
</script>

<template>
  <div class="summary-page eval-card">
    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-tree-select
        v-model="filters.chapterCode"
        :data="treeData"
        :props="treeProps"
        node-key="code"
        check-strictly
        clearable
        filterable
        size="small"
        placeholder="章节"
        style="width: 240px"
        @change="onSearch"
      />
      <el-input
        v-model="filters.specialty"
        size="small"
        clearable
        placeholder="专业"
        style="width: 140px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-select v-model="filters.extractMode" size="small" clearable placeholder="提取方式" style="width: 140px" @change="onSearch">
        <el-option label="系统提取" value="SYS" />
        <el-option label="人工填报" value="MANUAL" />
        <el-option label="系统+人工" value="HYBRID" />
        <el-option label="省级提取" value="PROV" />
      </el-select>
      <el-switch
        v-model="filters.zeroOnly"
        active-text="只看分子或分母为 0"
        @change="onSearch"
      />
      <el-input
        v-model="filters.keyword"
        size="small"
        clearable
        placeholder="关键字（编号/代号/名称）"
        style="width: 200px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-select v-model="filters.years" size="small" multiple collapse-tags placeholder="年份" style="width: 200px" @change="onSearch">
        <el-option v-for="y in ['2022', '2023', '2024', '2025']" :key="y" :label="y" :value="y" />
      </el-select>
      <el-button size="small" @click="onReset">重置</el-button>
      <div class="filter-right">
        <template v-if="mode === 'history'">
          <el-switch v-model="compareWithCurrent" active-text="与当期对比" />
          <el-select
            v-model="batchId"
            size="small"
            placeholder="选择批次（必选）"
            style="width: 320px"
          >
            <el-option v-for="b in batches" :key="b.batchId" :label="batchLabelOf(b)" :value="b.batchId" />
          </el-select>
        </template>
        <el-button v-if="canExport" size="small" type="primary" :loading="exporting" @click="onExportMaster">
          导出省评回填总表
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="mode === 'history' && !batchId"
      type="info"
      :closable="false"
      title="历史版本：请先在右上角选择一个批次再查询"
      class="mode-tip"
    />

    <!-- 主表格 -->
    <el-table
      v-loading="loading"
      :data="list"
      size="small"
      border
      row-key="metricId"
      @expand-change="onExpandChange"
    >
      <el-table-column type="expand" width="42">
        <template #default="{ row }">
          <div v-loading="expandLoading[row.metricId]" class="expand-box">
            <el-table
              :data="expandMap[row.metricId] || []"
              size="small"
              border
              max-height="320"
            >
              <el-table-column prop="deptName" label="科室" min-width="140" />
              <el-table-column label="免统" width="70">
                <template #default="{ row: sc }">
                  <el-tag v-if="sc.skipFlag" type="danger" size="small" disable-transitions>免统</el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <template v-for="y in filters.years" :key="y">
                <el-table-column :label="`${y} 分子`" width="110" align="center">
                  <template #default="{ row: sc }">
                    <el-link
                      v-if="sc.years && sc.years[y] && sc.years[y].num != null"
                      type="primary"
                      :underline="false"
                      @click="onScopeNumClick(sc, row, y)"
                    >{{ sc.years[y].num }}</el-link>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column :label="`${y} 分母`" width="110" align="center">
                  <template #default="{ row: sc }">
                    <el-link
                      v-if="sc.years && sc.years[y] && sc.years[y].den != null"
                      type="primary"
                      :underline="false"
                      @click="onScopeDenClick(sc, row, y)"
                    >{{ sc.years[y].den }}</el-link>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column :label="`${y} 状态`" width="110" align="center">
                  <template #default="{ row: sc }">
                    <AuditStatusTag v-if="sc.years && sc.years[y]" :status="sc.years[y].auditStatus" />
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
              </template>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="metricCode" label="指标编号" min-width="130" show-overflow-tooltip />
      <el-table-column prop="aliasCode" label="专科代号" min-width="100" show-overflow-tooltip />
      <el-table-column label="指标名称" min-width="220">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openVerify(row)">{{ row.metricName }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="提取方式" width="100">
        <template #default="{ row }">
          <ExtractModeTag :mode="row.extractMode" />
        </template>
      </el-table-column>
      <el-table-column label="科室数" width="70" align="center">
        <template #default="{ row }">{{ row.scopeCount ?? '—' }}</template>
      </el-table-column>
      <template v-for="y in filters.years" :key="y">
        <el-table-column :label="`${y} 分子`" width="100" align="center">
          <template #default="{ row }">
            <el-link
              v-if="yearCellOf(row, y) && yearCellOf(row, y).num != null"
              type="primary"
              :underline="false"
              @click="onNumClick(row, y)"
            >{{ fmtNum(yearCellOf(row, y).num) }}</el-link>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="`${y} 分母`" width="100" align="center">
          <template #default="{ row }">
            <el-link
              v-if="yearCellOf(row, y) && yearCellOf(row, y).den != null"
              type="primary"
              :underline="false"
              @click="onDenClick(row, y)"
            >{{ fmtNum(yearCellOf(row, y).den) }}</el-link>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="`${y} 结果值`" width="110" align="center">
          <template #default="{ row }">
            <div :class="{ 'diff-orange': isDiffFromCurrent(row, y) }">
              <div>{{ valTextOf(yearCellOf(row, y), row) }}</div>
              <div v-if="isDiffFromCurrent(row, y)" class="current-small">
                当期 {{ currentValText(row, y) }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="`${y} 状态`" width="110" align="center">
          <template #default="{ row }">
            <AuditStatusTag v-if="yearCellOf(row, y)" :status="yearCellOf(row, y).auditStatus" />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </template>
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

    <ExpertVerifyDrawer v-model:visible="verify.visible" :metric-id="verify.metricId" />

    <PatientDetailModal
      v-model:visible="detail.visible"
      :metric-id="detail.metricId"
      :scope-id="detail.scopeId"
      :year="detail.year"
      :role="detail.role"
      :batch-id="detail.batchId"
      :metric-name="detail.metricName"
      :dept-label="detail.deptLabel"
    />
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.filter-right {
  margin-left: auto;
  display: flex;
  gap: 10px;
  align-items: center;
}
.mode-tip {
  margin-bottom: 10px;
}
.expand-box {
  padding: 8px 16px;
}
.diff-orange {
  color: #e6a23c;
}
.current-small {
  font-size: 11px;
  color: #e6a23c;
}
.muted {
  color: #c0c4cc;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
