<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { workbenchOverview, workbenchTodos, workbenchDeptProgress, workbenchAlerts } from '@/api/report'
import { EXTRACT_MODE } from '@/utils/dict'

/**
 * 工作台（TASK-16 步骤 4）：统计卡片 / 复核进度环形图 + 我的待办 / 科室进度 / 异常预警。
 * 不放欢迎语、不放装饰图片。
 */
const router = useRouter()

const year = ref(2025)
const overview = ref(null)
const todos = ref([])
const deptRows = ref([])
const alerts = ref([])
const loading = ref(false)

const pieEl = ref(null)
let pieChart = null

const AUDIT_PIE = {
  DRAFT: { label: '草稿', color: '#909399' },
  L1_SUBMIT: { label: '已提交初审', color: '#409eff' },
  L2_PASS: { label: '内审通过', color: '#67c23a' },
  L2_REJECT: { label: '退回整改', color: '#f56c6c' },
  L3_ARCHIVED: { label: '已终审归档', color: '#2e7d32' }
}

// 待办 → 人工上报页状态 Tab 参数
const TODO_META = {
  TO_FILL: { label: '待填报', status: 'TODO', color: '#909399' },
  TO_RESUBMIT: { label: '被退回重报', status: 'REJECTED', color: '#f56c6c' },
  TO_AUDIT: { label: '待内审', status: 'SUBMITTED', color: '#409eff' },
  TO_ARCHIVE: { label: '待归档（内审已全部通过）', status: 'PASSED', color: '#67c23a' }
}

const ALERT_TYPE = {
  ZERO_DEN: { label: '分母为0', tag: 'danger' },
  ZERO_NUM: { label: '分子为0', tag: 'warning' },
  TASK_FAILED: { label: '任务失败', tag: 'danger' }
}

const extractCards = computed(() => {
  const byMode = (overview.value && overview.value.byExtractMode) || {}
  return Object.keys(EXTRACT_MODE).map((k) => ({
    key: k,
    label: EXTRACT_MODE[k].label,
    count: byMode[k] || 0,
    color: EXTRACT_MODE[k].tag
  }))
})

function percentColor(percent) {
  if (percent >= 100) return '#67c23a'
  if (percent >= 50) return '#e6a23c'
  return '#f56c6c'
}

function renderPie() {
  if (!pieChart || !overview.value) return
  const audit = overview.value.audit || {}
  const data = Object.keys(AUDIT_PIE)
    .map((k) => ({ name: AUDIT_PIE[k].label, value: audit[k] || 0, itemStyle: { color: AUDIT_PIE[k].color } }))
  pieChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, itemWidth: 12, itemHeight: 12 },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['50%', '45%'],
        label: { formatter: '{b}: {c}' },
        data
      }
    ]
  }, true)
}

function onResize() {
  pieChart && pieChart.resize()
}

async function loadAll() {
  loading.value = true
  try {
    const params = { year: year.value }
    overview.value = await workbenchOverview(params)
    todos.value = (await workbenchTodos(params)) || []
    deptRows.value = (await workbenchDeptProgress(params)) || []
    alerts.value = (await workbenchAlerts(params)) || []
    renderPie()
  } finally {
    loading.value = false
  }
}

function jumpTodo(todo) {
  const meta = TODO_META[todo.type]
  if (meta) {
    router.push({ path: '/manual', query: { status: meta.status } })
  }
}

function jumpAlert(a) {
  if (a.metricCode) {
    router.push({ path: '/summary/current', query: { keyword: a.metricCode } })
  }
}

watch(year, () => {
  loadAll()
})

onMounted(() => {
  pieChart = echarts.init(pieEl.value)
  window.addEventListener('resize', onResize)
  loadAll()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (pieChart) {
    pieChart.dispose()
    pieChart = null
  }
})
</script>

<template>
  <div v-loading="loading" class="workbench">
    <!-- 第一行：统计卡片 -->
    <div class="card-row">
      <div class="stat-card eval-card">
        <div class="stat-num">{{ overview ? overview.metricTotal : '—' }}</div>
        <div class="stat-label">评审指标总数</div>
      </div>
      <div class="stat-card eval-card">
        <div class="stat-num">{{ overview ? overview.applicable : '—' }}</div>
        <div class="stat-label">适用</div>
      </div>
      <div class="stat-card eval-card">
        <div class="stat-num">{{ overview ? overview.inapplicable : '—' }}</div>
        <div class="stat-label">不适用</div>
      </div>
      <div v-for="c in extractCards" :key="c.key" class="stat-card eval-card">
        <div class="stat-num">{{ c.count }}</div>
        <div class="stat-label">{{ c.label }}</div>
      </div>
    </div>

    <!-- 第二行：环形图 + 待办 -->
    <div class="row-2">
      <div class="eval-card pie-card">
        <h4 class="block-title">复核进度（{{ year }} 年当期）</h4>
        <div ref="pieEl" class="pie-box"></div>
      </div>
      <div class="eval-card todo-card">
        <h4 class="block-title">我的待办</h4>
        <div
          v-for="t in todos"
          :key="t.type"
          class="todo-item"
          @click="jumpTodo(t)"
        >
          <span class="todo-label">{{ TODO_META[t.type] ? TODO_META[t.type].label : t.type }}</span>
          <span class="todo-count" :style="{ background: TODO_META[t.type] ? TODO_META[t.type].color : '#909399' }">
            {{ t.count }}
          </span>
        </div>
        <div class="todo-hint">点击卡片进入【人工上报】对应状态列表</div>
      </div>
    </div>

    <!-- 第三行：科室进度 -->
    <div class="eval-card">
      <h4 class="block-title">科室进度</h4>
      <el-table :data="deptRows" size="small" border max-height="360">
        <el-table-column prop="deptName" label="科室" min-width="160" />
        <el-table-column prop="total" label="总数" width="70" align="center" />
        <el-table-column prop="submitted" label="已提交" width="80" align="center" />
        <el-table-column prop="passed" label="已通过" width="80" align="center" />
        <el-table-column prop="archived" label="已归档" width="80" align="center" />
        <el-table-column label="完成率" min-width="220">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.round((row.percent || 0) * 10) / 10"
              :color="percentColor(row.percent || 0)"
              :stroke-width="12"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 第四行：异常预警 -->
    <div class="eval-card">
      <h4 class="block-title">异常预警（≤200 条）</h4>
      <el-table :data="alerts" size="small" border max-height="320">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="(ALERT_TYPE[row.type] || {}).tag || 'info'" disable-transitions>
              {{ (ALERT_TYPE[row.type] || {}).label || row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标编号" width="140">
          <template #default="{ row }">
            <el-link v-if="row.metricCode" type="primary" :underline="false" @click="jumpAlert(row)">
              {{ row.metricCode }}
            </el-link>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="metricName" label="指标名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.metricName || '-' }}</template>
        </el-table-column>
        <el-table-column label="科室" width="140">
          <template #default="{ row }">{{ row.deptName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>
      <el-empty v-if="alerts.length === 0 && !loading" description="暂无预警" :image-size="48" />
    </div>
  </div>
</template>

<style scoped>
.card-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.stat-card {
  flex: 1;
  min-width: 120px;
  text-align: center;
  padding: 14px 8px;
}
.stat-num {
  font-size: 26px;
  font-weight: bold;
  color: #1f3a5f;
}
.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.row-2 {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.pie-card {
  flex: 1;
  min-width: 0;
}
.pie-box {
  height: 260px;
}
.todo-card {
  width: 320px;
  flex-shrink: 0;
}
.todo-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid #e6e8eb;
  border-radius: 4px;
  margin-bottom: 8px;
  cursor: pointer;
}
.todo-item:hover {
  border-color: #409eff;
  background: #f5f9ff;
}
.todo-label {
  font-size: 13px;
  color: #303133;
}
.todo-count {
  min-width: 32px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 11px;
  color: #fff;
  font-size: 12px;
  padding: 0 8px;
}
.todo-hint {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 6px;
}
.block-title {
  margin: 0 0 10px;
  color: #1f3a5f;
}
.eval-card {
  margin-bottom: 0;
}
.muted {
  color: #c0c4cc;
}
</style>
