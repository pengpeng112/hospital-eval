<script setup>
import { ref, watch } from 'vue'
import { listAuditLogs } from '@/api/report'
import { AUDIT_ACTION } from '@/utils/dict'

/**
 * 复核时间线抽屉（TASK-16 步骤 3）：listAuditLogs({resultId}) 或 ({metricId, year})。
 * 级别/动作中文映射按任务书文案；退回节点红色。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  resultId: { type: Number, default: null },
  metricId: { type: Number, default: null },
  year: { type: [Number, String], default: null }
})

const emit = defineEmits(['update:visible'])

/** 级别中文（任务书文案；dict.js 不在本任务白名单，故此映射定义在组件内）。 */
const LEVEL_LABEL = {
  L1_LIAISON: '科室初审',
  L2_AUDITOR: '内审复核',
  L3_FUNC: '职能终审',
  ADMIN: '管理员'
}

const logs = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  logs.value = []
  try {
    const params = {}
    if (props.resultId != null) {
      params.resultId = props.resultId
    } else if (props.metricId != null && props.year != null) {
      params.metricId = props.metricId
      params.year = props.year
    } else {
      return
    }
    logs.value = (await listAuditLogs(params)) || []
  } finally {
    loading.value = false
  }
}

function levelOf(code) {
  return LEVEL_LABEL[code] || code || '-'
}

function actionOf(code) {
  return AUDIT_ACTION[code] || code || '-'
}

function isReject(log) {
  return log.action === 'REJECT'
}

watch(() => props.visible, (v) => {
  if (v) load()
})
</script>

<template>
  <el-drawer
    :model-value="visible"
    size="480px"
    title="复核记录"
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-loading="loading">
      <el-timeline>
        <el-timeline-item
          v-for="(log, i) in logs"
          :key="i"
          :timestamp="log.operateTime"
          placement="top"
          :type="isReject(log) ? 'danger' : 'primary'"
          :hollow="!isReject(log)"
        >
          <div class="log-head">
            <b>{{ levelOf(log.auditLevel) }}</b>
            <el-tag size="small" :type="isReject(log) ? 'danger' : 'primary'" disable-transitions>
              {{ actionOf(log.action) }}
            </el-tag>
          </div>
          <div class="log-meta">
            操作人 {{ log.operatorName || '-' }}
            <span v-if="log.operatorDept"> · {{ log.operatorDept }}</span>
            <span v-if="log.operateIp"> · IP {{ log.operateIp }}</span>
          </div>
          <div v-if="log.opinion" class="log-opinion">意见：{{ log.opinion }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!loading && logs.length === 0" description="暂无复核记录" :image-size="60" />
    </div>
  </el-drawer>
</template>

<style scoped>
.log-head {
  display: flex;
  gap: 8px;
  align-items: center;
}
.log-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.log-opinion {
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}
</style>
