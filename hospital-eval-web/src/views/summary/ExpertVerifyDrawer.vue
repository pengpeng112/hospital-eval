<script setup>
import { computed, ref, watch } from 'vue'
import { getMetricPanel } from '@/api/result'
import { extractModeOf, AGG_MODE, SQL_ROLE, AUDIT_LEVEL, AUDIT_ACTION } from '@/utils/dict'
import ExtractModeTag from '@/components/ExtractModeTag.vue'
import TrendChart from '@/components/TrendChart.vue'
import SqlEditor from '@/components/SqlEditor.vue'

/**
 * 专家核验抽屉（TASK-15 步骤 3，宽 70%）：基本信息 / 口径 / 趋势 / 实际执行 SQL / 复核记录。
 * 数据来自 getMetricPanel(metricId)。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  metricId: { type: Number, default: null }
})

const emit = defineEmits(['update:visible'])

const panel = ref(null)
const loading = ref(false)
const sqlTab = ref('')

const roleLabelOf = (role) => SQL_ROLE[role] || role || '-'

// SQL 按科室分组（后端已按 scope_id, sql_role 排序）
const sqlGroups = computed(() => {
  const map = new Map()
  for (const s of (panel.value && panel.value.sqls) || []) {
    const dept = s.deptName || '全院/公共'
    if (!map.has(dept)) map.set(dept, [])
    map.get(dept).push(s)
  }
  return [...map.entries()].map(([dept, items]) => ({ dept, items }))
})

async function load() {
  if (!props.metricId) return
  loading.value = true
  panel.value = null
  try {
    panel.value = await getMetricPanel(props.metricId)
    sqlTab.value = sqlGroups.value.length > 0 ? sqlGroups.value[0].dept : ''
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => {
  if (v) load()
})
</script>

<template>
  <el-drawer
    :model-value="visible"
    size="70%"
    :title="panel ? `指标核验：${panel.dict.metricName || ''}` : '指标核验'"
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-loading="loading" class="verify-body">
      <template v-if="panel">
        <!-- 1. 基本信息 -->
        <h4 class="sec-title">基本信息</h4>
        <el-descriptions :column="4" size="small" border>
          <el-descriptions-item label="编号">{{ panel.dict.metricCode }}</el-descriptions-item>
          <el-descriptions-item label="代号">{{ panel.dict.aliasCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="名称" :span="2">{{ panel.dict.metricName }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ panel.dict.unit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="导向">{{ panel.dict.direction || '-' }}</el-descriptions-item>
          <el-descriptions-item label="归口部门">{{ panel.dict.ownerFuncDeptId || '未分配' }}</el-descriptions-item>
          <el-descriptions-item label="提取方式">
            <ExtractModeTag :mode="panel.dict.extractMode" :applicable="panel.dict.isApplicable" />
          </el-descriptions-item>
          <el-descriptions-item label="汇总方式">{{ AGG_MODE[panel.dict.aggMode] || panel.dict.aggMode || '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 2. 口径 -->
        <h4 class="sec-title">口径</h4>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="国家标准计算方法">
            <span class="pre-wrap">{{ panel.dict.stdFormulaDesc || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="本院统计口径">
            <span class="pre-wrap">{{ panel.dict.hospCaliberDesc || '-' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 3. 趋势 -->
        <h4 class="sec-title">趋势</h4>
        <TrendChart :data="panel.trend || []" />

        <!-- 4. 实际执行 SQL -->
        <h4 class="sec-title">实际执行 SQL</h4>
        <el-tabs v-if="sqlGroups.length > 0" v-model="sqlTab">
          <el-tab-pane v-for="g in sqlGroups" :key="g.dept" :label="g.dept" :name="g.dept">
            <div v-for="s in g.items" :key="s.sqlRole + s.periodStart" class="sql-block">
              <div class="sql-caption">
                <el-tag size="small" type="primary" disable-transitions>{{ roleLabelOf(s.sqlRole) }}</el-tag>
                <span class="muted">时段 {{ s.periodStart }}~{{ s.periodEnd }} · v{{ s.versionNo }}</span>
              </div>
              <SqlEditor :model-value="s.sqlAnnotated || ''" readonly height="240px" />
            </div>
          </el-tab-pane>
        </el-tabs>
        <el-empty v-else description="该指标无启用中的 SQL（人工填报指标）" :image-size="60" />

        <!-- 5. 复核记录 -->
        <h4 class="sec-title">复核记录</h4>
        <el-timeline>
          <el-timeline-item
            v-for="(log, i) in panel.auditLogs || []"
            :key="i"
            :timestamp="log.operateTime"
            placement="top"
            :type="log.action === 'REJECT' ? 'danger' : 'primary'"
          >
            <b>{{ AUDIT_LEVEL[log.auditLevel] || log.auditLevel }}</b>
            <el-tag size="small" class="log-tag" disable-transitions>
              {{ AUDIT_ACTION[log.action] || log.action }}
            </el-tag>
            <span class="muted">{{ log.operatorName }}</span>
            <div v-if="log.opinion" class="log-opinion">意见：{{ log.opinion }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-if="(panel.auditLogs || []).length === 0" description="暂无复核记录" :image-size="60" />
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.sec-title {
  margin: 16px 0 8px;
  color: #1f3a5f;
}
.pre-wrap {
  white-space: pre-wrap;
  word-break: break-all;
}
.sql-block {
  margin-bottom: 12px;
}
.sql-caption {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}
.log-tag {
  margin: 0 6px;
}
.log-opinion {
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
