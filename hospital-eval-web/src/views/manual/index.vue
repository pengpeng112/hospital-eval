<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listReportTasks, auditSubmit, auditApprove, auditReject, auditArchive, auditUnlock
} from '@/api/report'
import { useUserStore } from '@/stores/user'
import { roleOf } from '@/utils/dict'
import ExtractModeTag from '@/components/ExtractModeTag.vue'
import AuditStatusTag from '@/components/AuditStatusTag.vue'
import ReportDrawer from './ReportDrawer.vue'
import AuditTimelineDrawer from './AuditTimelineDrawer.vue'

/**
 * 人工上报主页面（TASK-16 步骤 1）：状态 Tab、勾选批量（提交/通过/退回）、
 * 行操作（填报/复核记录/终审归档/解锁）。状态流转一律调后端接口。
 */
const route = useRoute()
const store = useUserStore()
const isAdmin = computed(() => store.hasRole('ADMIN'))
const isFuncLeader = computed(() => store.hasRole('FUNC_LEADER'))

const STATUS_TABS = [
  { label: '待填报', value: 'TODO' },
  { label: '被退回', value: 'REJECTED' },
  { label: '已提交', value: 'SUBMITTED' },
  { label: '内审通过', value: 'PASSED' },
  { label: '已归档', value: 'ARCHIVED' },
  { label: '全部', value: 'ALL' }
]

const query = reactive({ page: 1, size: 20, year: 2025, status: 'TODO', keyword: '' })
const list = ref([])
const total = ref(0)
const loading = ref(false)
const selected = ref([])

async function load() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size, year: query.year, status: query.status }
    if (query.keyword) params.keyword = query.keyword
    const data = await listReportTasks(params)
    list.value = data.list || []
    total.value = data.total || 0
    selected.value = []
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  query.page = 1
  load()
}

function onSearch() {
  query.page = 1
  load()
}

function onReset() {
  query.keyword = ''
  onSearch()
}

function onSelectionChange(rows) {
  selected.value = rows
}

const selectedIds = computed(() =>
  selected.value.map((r) => r.resultId).filter((id) => id != null)
)

// ---------------- 批量操作 ----------------
const batchResult = reactive({ visible: false, action: '', successCount: 0, failed: [] })
const rejectDialog = reactive({ visible: false, opinion: '', doing: false })

function canBatchDo(action) {
  if (selectedIds.value.length === 0) return false
  if (action === 'SUBMIT') {
    // SUBMIT：DRAFT / L2_REJECT / 无结果行（resultId 为空会在提交时被后端拒绝并列出原因）
    return selected.value.every((r) => r.auditStatus == null
      || r.auditStatus === 'DRAFT' || r.auditStatus === 'L2_REJECT')
  }
  if (action === 'APPROVE' || action === 'REJECT') {
    return selected.value.every((r) => r.auditStatus === 'L1_SUBMIT')
  }
  return false
}

function openBatch(action) {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请先勾选已填报的行（未填报行无结果 ID）')
    return
  }
  if (action === 'REJECT') {
    rejectDialog.opinion = ''
    rejectDialog.visible = true
    return
  }
  doBatch(action)
}

async function doBatch(action, opinion = null) {
  let resp
  if (action === 'SUBMIT') {
    resp = await auditSubmit({ resultIds: selectedIds.value })
  } else if (action === 'APPROVE') {
    resp = await auditApprove({ resultIds: selectedIds.value })
  } else if (action === 'REJECT') {
    resp = await auditReject({ resultIds: selectedIds.value, opinion })
  } else {
    return
  }
  batchResult.action = action
  batchResult.successCount = (resp.success || []).length
  batchResult.failed = resp.failed || []
  batchResult.visible = true
  load()
}

async function onRejectConfirm() {
  if (!rejectDialog.opinion || rejectDialog.opinion.trim().length < 5) {
    ElMessage.warning('退回意见至少 5 个字')
    return
  }
  rejectDialog.doing = true
  try {
    await doBatch('REJECT', rejectDialog.opinion.trim())
    rejectDialog.visible = false
  } finally {
    rejectDialog.doing = false
  }
}

const ACTION_LABEL = { SUBMIT: '提交初审', APPROVE: '内审通过', REJECT: '退回整改' }

// ---------------- 行操作 ----------------
const drawer = reactive({ visible: false, row: null })
const timeline = reactive({ visible: false, resultId: null, metricId: null, year: null })

function openFill(row) {
  drawer.row = row
  drawer.visible = true
}

function openTimeline(row) {
  timeline.resultId = row.resultId
  timeline.metricId = row.metricId
  timeline.year = query.year
  timeline.visible = true
}

const canArchive = computed(() => isAdmin.value || isFuncLeader.value)

async function onArchive(row) {
  try {
    await ElMessageBox.confirm(
      `按指标整体归档「${row.metricName || ''}」${query.year} 年数据？归档后所有科室行 + 全院行锁定。`,
      '终审归档',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  // 失败时后端返回的未通过科室列表经拦截器完整弹出（msg 全文）
  await auditArchive({ metricId: row.metricId, year: query.year })
  ElMessage.success('归档成功')
  load()
}

async function onUnlock(row) {
  try {
    const { value } = await ElMessageBox.prompt('解锁将把该指标该年全部归档行重置为草稿，请填写解锁意见', '解锁', {
      inputValidator: (v) => (v && v.trim() ? true : '解锁意见必填')
    })
    await auditUnlock({ metricId: row.metricId, year: query.year, opinion: value.trim() })
    ElMessage.success('已解锁')
    load()
  } catch (e) {
    // 取消或失败（失败已由拦截器弹出）
  }
}

onMounted(() => {
  // 工作台待办跳转带状态参数（04 §13 页面间跳转参数）
  if (route.query.status && STATUS_TABS.some((t) => t.value === route.query.status)) {
    query.status = route.query.status
  }
  load()
})
</script>

<template>
  <div class="manual-page eval-card">
    <div class="toolbar">
      <el-select v-model="query.year" size="small" style="width: 110px" @change="onSearch">
        <el-option v-for="y in [2022, 2023, 2024, 2025]" :key="y" :label="y" :value="y" />
      </el-select>
      <el-input
        v-model="query.keyword"
        size="small"
        clearable
        placeholder="指标编号 / 名称 / 科室"
        style="width: 220px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button size="small" @click="onReset">重置</el-button>
      <div class="batch-bar">
        <el-button size="small" type="primary" :disabled="!canBatchDo('SUBMIT')" @click="openBatch('SUBMIT')">
          提交初审
        </el-button>
        <el-button size="small" type="success" :disabled="!canBatchDo('APPROVE')" @click="openBatch('APPROVE')">
          内审通过
        </el-button>
        <el-button size="small" type="warning" :disabled="!canBatchDo('REJECT')" @click="openBatch('REJECT')">
          退回整改
        </el-button>
      </div>
    </div>

    <el-tabs v-model="query.status" @tab-change="onTabChange">
      <el-tab-pane v-for="t in STATUS_TABS" :key="t.value" :label="t.label" :name="t.value" />
    </el-tabs>

    <el-table
      v-loading="loading"
      :data="list"
      size="small"
      border
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="42" />
      <el-table-column prop="metricCode" label="指标编号" min-width="130" show-overflow-tooltip />
      <el-table-column prop="metricName" label="指标名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="deptName" label="科室" min-width="120" />
      <el-table-column label="提取方式" width="100">
        <template #default="{ row }">
          <ExtractModeTag :mode="row.extractMode" />
        </template>
      </el-table-column>
      <el-table-column label="我的角色" width="100">
        <template #default="{ row }">{{ row.myRole ? roleOf(row.myRole) : '-' }}</template>
      </el-table-column>
      <el-table-column label="分子" width="90" align="center">
        <template #default="{ row }">{{ row.num != null ? row.num : '—' }}</template>
      </el-table-column>
      <el-table-column label="分母" width="90" align="center">
        <template #default="{ row }">{{ row.den != null ? row.den : '—' }}</template>
      </el-table-column>
      <el-table-column label="结果值" width="100" align="center">
        <template #default="{ row }">{{ row.val != null ? row.val : '—' }}</template>
      </el-table-column>
      <el-table-column prop="evidenceCount" label="附件数" width="70" align="center" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <AuditStatusTag :status="row.auditStatus || 'DRAFT'" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openFill(row)">填报</el-button>
          <el-button v-if="row.resultId" link size="small" @click="openTimeline(row)">复核记录</el-button>
          <el-button
            v-if="canArchive && (row.myRole === 'FUNC_LEADER' || isAdmin)"
            link
            type="danger"
            size="small"
            @click="onArchive(row)"
          >终审归档</el-button>
          <el-button
            v-if="(isAdmin || isFuncLeader) && row.auditStatus === 'L3_ARCHIVED'"
            link
            type="warning"
            size="small"
            @click="onUnlock(row)"
          >解锁</el-button>
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
      @current-change="load"
    />

    <ReportDrawer
      v-model:visible="drawer.visible"
      :row="drawer.row"
      :year="query.year"
      @refresh="load"
    />

    <AuditTimelineDrawer
      v-model:visible="timeline.visible"
      :result-id="timeline.resultId"
      :metric-id="timeline.metricId"
      :year="timeline.year"
    />

    <!-- 批量执行结果弹窗 -->
    <el-dialog v-model="batchResult.visible" :title="`${ACTION_LABEL[batchResult.action]} 执行结果`" width="560px">
      <p>成功 <b>{{ batchResult.successCount }}</b> 条</p>
      <template v-if="batchResult.failed.length > 0">
        <p class="err-title">失败（{{ batchResult.failed.length }}）：</p>
        <el-table :data="batchResult.failed" size="small" border max-height="240">
          <el-table-column prop="resultId" label="结果ID" width="90" />
          <el-table-column prop="reason" label="原因" min-width="280" />
        </el-table>
      </template>
      <template #footer>
        <el-button type="primary" @click="batchResult.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 退回意见弹窗 -->
    <el-dialog v-model="rejectDialog.visible" title="退回整改（意见必填）" width="480px">
      <el-input
        v-model="rejectDialog.opinion"
        type="textarea"
        :rows="3"
        placeholder="至少 5 个字，说明退回原因"
      />
      <template #footer>
        <el-button @click="rejectDialog.visible = false">取消</el-button>
        <el-button type="warning" :loading="rejectDialog.doing" @click="onRejectConfirm">退回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}
.batch-bar {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.err-title {
  color: #c0392b;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
