<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  downloadReconcileTemplate, importReconcile, listReconcileImports, listReconcileRows, exportReconcile
} from '@/api/dashboard'
import { useUserStore } from '@/stores/user'

/**
 * 省平台对账专区（TASK-17 步骤 4）：模板下载、导入（ADMIN/FUNC_LEADER）、
 * 批次下拉、统计条（含分母非零占比）、明细表（只看问题项）、导出差异清单。
 */
const router = useRouter()
const store = useUserStore()
const canImport = computed(() => store.hasRole('ADMIN', 'FUNC_LEADER'))

const imports = ref([])
const importId = ref(null)
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const onlyIssue = ref(false)
const query = reactive({ page: 1, size: 20 })

const FLAG_META = {
  OK: { label: '一致', tag: 'info' },
  PROV_DEN_ZERO: { label: '省分母为0', tag: 'danger' },
  PROV_NUM_ZERO: { label: '省分子为0', tag: 'danger' },
  DIFF_GT_5: { label: '院省偏差>5%', tag: 'warning' },
  NOT_FOUND: { label: '院内无此指标', tag: 'info' }
}

const stats = reactive({ ok: 0, denZero: 0, numZero: 0, diff: 0, notFound: 0 })

const denNonZeroRate = computed(() => {
  if (!total.value) return null
  return Math.round(((total.value - stats.denZero) * 1000 / total.value)) / 10
})

function flagMetaOf(flag) {
  return FLAG_META[flag] || { label: flag || '-', tag: 'info' }
}

async function loadImports() {
  imports.value = (await listReconcileImports()) || []
  if (imports.value.length > 0 && !importId.value) {
    importId.value = imports.value[0].importId
  }
}

async function loadRows() {
  if (!importId.value) {
    rows.value = []
    total.value = 0
    resetStats()
    return
  }
  loading.value = true
  try {
    const data = await listReconcileRows(importId.value, {
      onlyIssue: onlyIssue.value || undefined,
      page: query.page,
      size: query.size
    })
    rows.value = data.list || []
    total.value = data.total || 0
    // 统计条始终按全量行统计（onlyIssue 只影响表格）
    await loadStats()
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  // 统计条始终按该批次全量行统计（导入量级为百级，一次拉全量计数）
  const all = await listReconcileRows(importId.value, { page: 1, size: 5000 })
  const flagCounts = { OK: 0, PROV_DEN_ZERO: 0, PROV_NUM_ZERO: 0, DIFF_GT_5: 0, NOT_FOUND: 0 }
  for (const r of all.list || []) {
    if (flagCounts[r.flag] !== undefined) {
      flagCounts[r.flag]++
    }
  }
  stats.ok = flagCounts.OK
  stats.denZero = flagCounts.PROV_DEN_ZERO
  stats.numZero = flagCounts.PROV_NUM_ZERO
  stats.diff = flagCounts.DIFF_GT_5
  stats.notFound = flagCounts.NOT_FOUND
  total.value = all.total || 0
}

function resetStats() {
  stats.ok = 0
  stats.denZero = 0
  stats.numZero = 0
  stats.diff = 0
  stats.notFound = 0
}

function onSearch() {
  query.page = 1
  loadRows()
}

async function onImport(option) {
  try {
    const data = await importReconcile(option.file)
    ElMessage.success(`导入完成：共 ${data.total} 行，问题项 ${data.issueCount} 行（${data.importId}）`)
    await loadImports()
    importId.value = data.importId
    query.page = 1
    onlyIssue.value = false
    loadRows()
  } catch (e) {
    // 错误已由拦截器弹出
  }
  return Promise.resolve()
}

function jumpMetric(row) {
  if (row.metricCode) {
    router.push({ path: '/summary/current', query: { keyword: row.metricCode } })
  }
}

onMounted(async () => {
  await loadImports()
  loadRows()
})
</script>

<template>
  <div class="reconcile eval-card">
    <div class="toolbar">
      <el-button size="small" type="primary" plain @click="downloadReconcileTemplate()">下载模板</el-button>
      <el-upload
        v-if="canImport"
        :show-file-list="false"
        accept=".xlsx"
        :http-request="onImport"
      >
        <el-button size="small" type="success">导入省平台反馈表</el-button>
      </el-upload>
      <el-select
        v-model="importId"
        size="small"
        placeholder="选择导入批次"
        style="width: 340px"
        @change="onSearch"
      >
        <el-option
          v-for="im in imports"
          :key="im.importId"
          :label="`${im.importId} · ${im.importUser || ''} · ${im.importTime || ''} · 共${im.total} 问题${im.issueCount}`"
          :value="im.importId"
        />
      </el-select>
      <div class="toolbar-right">
        <el-switch v-model="onlyIssue" active-text="只看问题项" @change="onSearch" />
        <el-button size="small" type="warning" plain :disabled="!importId" @click="exportReconcile(importId)">
          导出差异清单
        </el-button>
      </div>
    </div>

    <!-- 统计条 -->
    <div class="stat-bar">
      <span class="stat-item"><b>{{ total }}</b> 总数</span>
      <span class="stat-item ok"><b>{{ stats.ok }}</b> 一致</span>
      <span class="stat-item danger"><b>{{ stats.denZero }}</b> 省分母为 0</span>
      <span class="stat-item danger"><b>{{ stats.numZero }}</b> 省分子为 0</span>
      <span class="stat-item warn"><b>{{ stats.diff }}</b> 偏差 &gt;5%</span>
      <span class="stat-item gray"><b>{{ stats.notFound }}</b> 院内无此指标</span>
      <span class="stat-item rate">
        分母非零占比：<b>{{ denNonZeroRate == null ? '—' : denNonZeroRate + '%' }}</b>
        <span class="rate-formula">= (总数 − 省分母为0) / 总数</span>
      </span>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" border>
      <el-table-column label="指标编号" width="150">
        <template #default="{ row }">
          <el-link v-if="row.metricCode" type="primary" :underline="false" @click="jumpMetric(row)">
            {{ row.metricCode }}
          </el-link>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="metricName" label="指标名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="statYear" label="年份" width="70" align="center" />
      <el-table-column label="省分子" width="90" align="center">
        <template #default="{ row }">{{ row.provNum ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="省分母" width="90" align="center">
        <template #default="{ row }">{{ row.provDen ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="省结果值" width="100" align="center">
        <template #default="{ row }">{{ row.provVal ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="院内分子" width="90" align="center">
        <template #default="{ row }">{{ row.hospNum ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="院内分母" width="90" align="center">
        <template #default="{ row }">{{ row.hospDen ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="院内结果值" width="100" align="center">
        <template #default="{ row }">{{ row.hospVal ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="问题类型" width="120" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="flagMetaOf(row.flag).tag" disable-transitions>
            {{ flagMetaOf(row.flag).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      class="pager"
      background
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.size"
      @current-change="loadRows"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 12px;
  align-items: center;
}
.stat-bar {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  background: #f5f7fa;
  border-radius: 4px;
  padding: 10px 14px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #606266;
}
.stat-item b {
  font-size: 16px;
  margin-right: 4px;
}
.stat-item.ok b {
  color: #67c23a;
}
.stat-item.danger b {
  color: #f56c6c;
}
.stat-item.warn b {
  color: #e6a23c;
}
.stat-item.gray b {
  color: #909399;
}
.rate-formula {
  color: #c0c4cc;
  font-size: 11px;
  margin-left: 4px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.muted {
  color: #c0c4cc;
}
</style>
