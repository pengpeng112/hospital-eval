<script setup>
import { reactive, ref, watch } from 'vue'
import { listDetail, exportDetail } from '@/api/result'
import EmrRecordDrawer from './EmrRecordDrawer.vue'

/**
 * 患者明细弹窗（TASK-15 步骤 4）：四级下钻第三层。
 * 列由后端 columns 动态生成，prop 以 ext. 开头的从 row.ext 取值；
 * 住院号为链接 → 打开病历抽屉；顶部隐私提示。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  metricId: { type: Number, default: null },
  scopeId: { type: Number, default: 0 },
  year: { type: String, default: '' },
  role: { type: String, default: 'NUM' },
  batchId: { type: Number, default: null },
  metricName: { type: String, default: '' },
  deptLabel: { type: String, default: '全院' }
})

const emit = defineEmits(['update:visible'])

const query = reactive({ page: 1, size: 50, keyword: '' })
const columns = ref([])
const list = ref([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)

const emr = reactive({ visible: false, patientId: '', visitId: '', inpNo: '' })

function close() {
  emit('update:visible', false)
}

function roleLabel() {
  return props.role === 'DEN' ? '分母' : '分子'
}

async function load() {
  if (!props.metricId || !props.year) return
  loading.value = true
  try {
    const params = {
      metricId: props.metricId,
      scopeId: props.scopeId,
      year: props.year,
      role: props.role,
      page: query.page,
      size: query.size
    }
    if (props.batchId) params.batchId = props.batchId
    if (query.keyword) params.keyword = query.keyword
    const data = await listDetail(params)
    columns.value = data.columns || []
    list.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function cellValue(row, prop) {
  if (prop.startsWith('ext.')) {
    const key = prop.slice(4)
    const ext = row.ext || {}
    return ext[key] == null ? '' : String(ext[key])
  }
  const v = row[prop]
  return v == null ? '' : String(v)
}

function onSearch() {
  query.page = 1
  load()
}

async function onExport() {
  exporting.value = true
  try {
    const params = {
      metricId: props.metricId,
      scopeId: props.scopeId,
      year: props.year,
      role: props.role
    }
    if (props.batchId) params.batchId = props.batchId
    if (query.keyword) params.keyword = query.keyword
    await exportDetail(params)
  } finally {
    exporting.value = false
  }
}

function openEmr(row) {
  emr.patientId = row.patientId || ''
  emr.visitId = row.visitId || ''
  emr.inpNo = row.inpNo || ''
  emr.visible = true
}

watch(() => props.visible, (v) => {
  if (v) {
    query.page = 1
    query.keyword = ''
    load()
  }
})
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="`${metricName || '指标'} · ${deptLabel || '全院'} · ${year} · ${roleLabel()}明细`"
    width="88%"
    top="4vh"
    @update:model-value="emit('update:visible', $event)"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="含患者隐私信息，仅限院内核验使用"
      class="privacy"
    />
    <div class="toolbar">
      <span class="total-hint">共 <b>{{ total }}</b> 人次（按 患者ID+住院次数 去重）</span>
      <el-input
        v-model="query.keyword"
        size="small"
        clearable
        placeholder="关键字（住院号/姓名/诊断等）"
        style="width: 240px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button size="small" type="primary" @click="onSearch">查询</el-button>
      <el-button size="small" :loading="exporting" @click="onExport">导出 Excel</el-button>
    </div>

    <el-table v-loading="loading" :data="list" size="small" border max-height="480">
      <el-table-column
        v-for="col in columns"
        :key="col.prop"
        :label="col.label"
        :prop="col.prop.startsWith('ext.') ? undefined : col.prop"
        min-width="130"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <el-link
            v-if="col.prop === 'inpNo' && row.inpNo"
            type="primary"
            :underline="false"
            @click="openEmr(row)"
          >{{ row.inpNo }}</el-link>
          <span v-else>{{ cellValue(row, col.prop) }}</span>
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

    <EmrRecordDrawer
      v-model:visible="emr.visible"
      :patient-id="emr.patientId"
      :visit-id="emr.visitId"
      :inp-no="emr.inpNo"
      :metric-id="metricId"
    />
  </el-dialog>
</template>

<style scoped>
.privacy {
  margin-bottom: 10px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.total-hint {
  color: #606266;
  font-size: 13px;
  margin-right: auto;
}
.pager {
  margin-top: 10px;
  justify-content: flex-end;
}
</style>
