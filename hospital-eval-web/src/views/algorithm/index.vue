<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDatasources, pingDatasource, validateSql, trialSql,
  listSqls, getSql, createSql, updateSql, toggleSql, listSqlHistory, getSqlHis
} from '@/api/algo'
import { runBatch, getBatch, listBatchTasks } from '@/api/batch'
import { getMetric } from '@/api/metric'
import { useUserStore } from '@/stores/user'
import { SQL_ROLE } from '@/utils/dict'
import MetricPicker from '@/components/MetricPicker.vue'
import SqlEditor from '@/components/SqlEditor.vue'
import SqlDiffView from '@/components/SqlDiffView.vue'

const store = useUserStore()
const isAdmin = computed(() => store.hasRole('ADMIN'))

const ROLE_OPTIONS = Object.keys(SQL_ROLE)
const CHANGE_TYPE_OPTIONS = ['口径升级', '算法Bug修复']
const YEAR_OPTIONS = [2022, 2023, 2024, 2025]

// ---------------- 顶部条件 ----------------
const metricId = ref(null)
const selectedMetric = ref(null)
const scopeOptions = ref([])
const scopeId = ref('') // '' = 全部科室
const datasourceList = ref([])
const pingResults = reactive({})

async function onMetricSelect(row) {
  selectedMetric.value = row
  metricId.value = row ? row.id : null
  scopeId.value = ''
  clearSelection()
  scopeOptions.value = []
  if (metricId.value) {
    const detail = await getMetric(metricId.value)
    scopeOptions.value = (detail.scopes || []).filter((s) => !s.skipFlag)
  }
  loadSqlList()
}

async function onScopeChange() {
  clearSelection()
  loadSqlList()
}

async function loadDatasources() {
  datasourceList.value = (await listDatasources()) || []
}

async function onPing(ds) {
  const resp = await pingDatasource(ds.key)
  pingResults[ds.key] = resp
}

// ---------------- 左侧 SQL 列表 ----------------
const sqlList = ref([])
const sqlLoading = ref(false)

const groupedSqls = computed(() => {
  const map = new Map()
  for (const item of sqlList.value) {
    const dept = item.deptName || `实例 #${item.scopeId}`
    if (!map.has(dept)) map.set(dept, [])
    map.get(dept).push(item)
  }
  return [...map.entries()].map(([dept, items]) => ({ dept, items }))
})

async function loadSqlList() {
  if (!metricId.value) {
    sqlList.value = []
    return
  }
  sqlLoading.value = true
  try {
    const params = { metricId: metricId.value }
    if (scopeId.value !== '') params.scopeId = scopeId.value
    sqlList.value = (await listSqls(params)) || []
  } finally {
    sqlLoading.value = false
  }
}

function roleLabel(role) {
  return SQL_ROLE[role] || role || '-'
}

/** 校验状态：1 试跑通过绿 / 2 试跑报错红 / 其余（null、0 待校验）灰。 */
function verifyTag(status) {
  if (status === 1) return { label: '校验通过', tag: 'success' }
  if (status === 2) return { label: '校验报错', tag: 'danger' }
  return { label: '未校验', tag: 'info' }
}

// ---------------- 右侧编辑区 ----------------
const selected = ref(null) // getSql 全量字段
const sqlText = ref('')
const originalText = ref('')
const detailLoading = ref(false)
const validateResult = ref(null)
const validating = ref(false)

async function onSelectSql(row) {
  validateResult.value = null
  detailLoading.value = true
  try {
    selected.value = await getSql(row.id)
    sqlText.value = selected.value.sqlAnnotated || ''
    originalText.value = sqlText.value
  } finally {
    detailLoading.value = false
  }
}

function clearSelection() {
  selected.value = null
  sqlText.value = ''
  originalText.value = ''
  validateResult.value = null
}

function requireSelection() {
  if (!selected.value) {
    ElMessage.warning('请先在左侧选择一条 SQL')
    return false
  }
  return true
}

async function onValidate() {
  if (!requireSelection()) return
  validating.value = true
  try {
    validateResult.value = await validateSql({ sqlText: sqlText.value })
  } finally {
    validating.value = false
  }
}

// 试跑（仅 ADMIN）
const trial = reactive({ visible: false, loading: false, columns: [], rows: [], rowCount: 0, elapsedMs: 0 })

// 后端返回 columns（列名数组）+ rows（行数组）→ 转对象数组供 el-table 渲染
const trialTableData = computed(() =>
  trial.rows.map((row) => {
    const obj = {}
    trial.columns.forEach((col, i) => {
      obj['c' + i] = row[i]
    })
    return obj
  })
)

async function onTrial() {
  if (!requireSelection()) return
  if (!sqlText.value.trim()) {
    ElMessage.warning('SQL 内容为空')
    return
  }
  trial.loading = true
  try {
    const data = await trialSql({
      sqlText: sqlText.value,
      datasourceKey: selected.value.datasourceKey,
      sqlId: selected.value.id
    })
    trial.columns = data.columns || []
    trial.rows = data.rows || []
    trial.rowCount = data.rowCount || 0
    trial.elapsedMs = data.elapsedMs || 0
    trial.visible = true
    loadSqlList() // 试跑会回写 last_verify_status，刷新左侧状态
  } finally {
    trial.loading = false
  }
}

// 保存
const saveModal = reactive({ visible: false, changeType: '', changeReason: '', saving: false })

function onSaveOpen() {
  if (!requireSelection()) return
  if (!isAdmin.value) return
  saveModal.changeType = ''
  saveModal.changeReason = ''
  saveModal.visible = true
}

async function onSaveSubmit() {
  if (!CHANGE_TYPE_OPTIONS.includes(saveModal.changeType)) {
    ElMessage.warning('请选择修改类型')
    return
  }
  if (!saveModal.changeReason || saveModal.changeReason.trim().length < 5) {
    ElMessage.warning('修改原因至少 5 个字')
    return
  }
  saveModal.saving = true
  try {
    await updateSql(selected.value.id, {
      periodStart: selected.value.periodStart,
      periodEnd: selected.value.periodEnd,
      sqlAnnotated: sqlText.value,
      changeType: saveModal.changeType,
      changeReason: saveModal.changeReason.trim()
    })
    ElMessage.success('保存成功，版本号已递增')
    saveModal.visible = false
    await loadSqlList()
    const row = sqlList.value.find((s) => s.id === selected.value.id)
    if (row) await onSelectSql(row)
  } finally {
    saveModal.saving = false
  }
}

// 新增 SQL
const createModal = reactive({
  visible: false, sqlRole: '', datasourceKey: '', periodStart: '', periodEnd: '',
  sqlAnnotated: '', changeReason: '', saving: false
})

function onCreateOpen() {
  if (!metricId.value) {
    ElMessage.warning('请先选择指标')
    return
  }
  if (scopeId.value === '') {
    ElMessage.warning('新增 SQL 需先在顶部选择具体科室实例（SQL 挂载到科室实例）')
    return
  }
  createModal.sqlRole = ''
  createModal.datasourceKey = ''
  createModal.periodStart = ''
  createModal.periodEnd = ''
  createModal.sqlAnnotated = ''
  createModal.changeReason = ''
  createModal.visible = true
}

function validPeriod(v) {
  return /^\d{6}$/.test(v || '')
}

async function onCreateSubmit() {
  if (!createModal.sqlRole) {
    ElMessage.warning('请选择 SQL 角色')
    return
  }
  if (!createModal.datasourceKey) {
    ElMessage.warning('请选择数据源')
    return
  }
  if (!validPeriod(createModal.periodStart) || !validPeriod(createModal.periodEnd)) {
    ElMessage.warning('起止年月格式为 6 位数字，如 202201')
    return
  }
  if (!createModal.sqlAnnotated.trim()) {
    ElMessage.warning('SQL 内容为空')
    return
  }
  if (!createModal.changeReason || createModal.changeReason.trim().length < 1) {
    ElMessage.warning('请填写修改原因')
    return
  }
  createModal.saving = true
  try {
    await createSql({
      metricId: metricId.value,
      scopeId: scopeId.value,
      sqlRole: createModal.sqlRole,
      datasourceKey: createModal.datasourceKey,
      periodStart: createModal.periodStart,
      periodEnd: createModal.periodEnd,
      sqlAnnotated: createModal.sqlAnnotated,
      changeReason: createModal.changeReason.trim()
    })
    ElMessage.success('新增成功（version=1）')
    createModal.visible = false
    loadSqlList()
  } finally {
    createModal.saving = false
  }
}

// 启用 / 停用
const toggleModal = reactive({ visible: false, active: false, changeReason: '', saving: false })

function onToggleOpen() {
  if (!requireSelection() || !isAdmin.value) return
  toggleModal.active = !selected.value.isActive
  toggleModal.changeReason = ''
  toggleModal.visible = true
}

async function onToggleSubmit() {
  if (!toggleModal.changeReason || !toggleModal.changeReason.trim()) {
    ElMessage.warning('启用/停用原因必填')
    return
  }
  toggleModal.saving = true
  try {
    await toggleSql(selected.value.id, {
      active: toggleModal.active,
      changeReason: toggleModal.changeReason.trim()
    })
    ElMessage.success(toggleModal.active ? '已启用' : '已停用')
    toggleModal.visible = false
    await loadSqlList()
    const row = sqlList.value.find((s) => s.id === selected.value.id)
    if (row) await onSelectSql(row)
  } finally {
    toggleModal.saving = false
  }
}

// 历史版本
const history = reactive({ visible: false, loading: false, list: [], diff: null })

async function onHistoryOpen() {
  if (!requireSelection()) return
  history.diff = null
  history.visible = true
  history.loading = true
  try {
    history.list = (await listSqlHistory(selected.value.id)) || []
  } finally {
    history.loading = false
  }
}

async function onHisClick(his) {
  const detail = await getSqlHis(his.hisId)
  history.diff = {
    versionNo: his.versionNo,
    old: detail.oldSqlAnnotated || '',
    new: detail.newSqlAnnotated || ''
  }
}

// 覆盖评审数据（仅 ADMIN）
const batch = reactive({
  visible: false, scopeType: 'scope', years: [2025], versionTag: '',
  running: false, batchId: null, stats: null, progress: 0, done: false,
  failedList: [], failedLoading: false, batchNo: ''
})
let pollTimer = null

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function openBatchModal() {
  batch.scopeType = 'scope'
  batch.years = [2025]
  batch.versionTag = ''
  batch.running = false
  batch.batchId = null
  batch.batchNo = ''
  batch.stats = null
  batch.progress = 0
  batch.done = false
  batch.failedList = []
  batch.visible = true
}

watch(() => batch.visible, (visible) => {
  // 关闭弹窗即停止轮询
  if (!visible) stopPoll()
})

async function onBatchConfirm() {
  if (batch.scopeType === 'scope' && scopeId.value === '') {
    ElMessage.warning('当前为"全部科室"，请先在顶部选择具体科室实例，或改选"当前指标全部科室"')
    return
  }
  if (!batch.years || batch.years.length === 0) {
    ElMessage.warning('请至少选择一个年份')
    return
  }
  const payload = { years: batch.years }
  if (batch.scopeType === 'scope') {
    payload.scopeIds = [scopeId.value]
  } else {
    payload.metricIds = [metricId.value]
  }
  if (batch.versionTag && batch.versionTag.trim()) {
    payload.versionTag = batch.versionTag.trim()
  }
  const data = await runBatch(payload)
  batch.batchId = data.batchId
  batch.batchNo = data.batchNo
  batch.running = true
  batch.done = false
  pollBatch()
  pollTimer = setInterval(pollBatch, 5000)
}

async function pollBatch() {
  if (!batch.batchId) return
  const data = await getBatch(batch.batchId)
  batch.stats = data.taskStats
  const s = data.taskStats || {}
  const total = (s.PENDING || 0) + (s.RUNNING || 0) + (s.SUCCESS || 0) + (s.FAILED || 0) + (s.SKIPPED || 0)
  const finished = (s.SUCCESS || 0) + (s.FAILED || 0) + (s.SKIPPED || 0)
  batch.progress = total === 0 ? 0 : Math.round((finished * 1000) / total) / 10
  if (total > 0 && (s.PENDING || 0) + (s.RUNNING || 0) === 0) {
    batch.done = true
    batch.running = false
    stopPoll()
    loadFailedTasks()
  }
}

async function loadFailedTasks() {
  batch.failedLoading = true
  try {
    const data = await listBatchTasks(batch.batchId, { page: 1, size: 100, status: 'FAILED' })
    batch.failedList = data.list || []
  } finally {
    batch.failedLoading = false
  }
}

onBeforeUnmount(stopPoll)

onMounted(loadDatasources)
</script>

<template>
  <div class="algo-page">
    <!-- 顶部条件栏 -->
    <div class="top-bar eval-card">
      <div class="top-row">
        <MetricPicker v-model="metricId" @select="onMetricSelect" />
        <el-select
          v-model="scopeId"
          placeholder="科室实例"
          size="small"
          style="width: 200px"
          :disabled="!metricId"
          @change="onScopeChange"
        >
          <el-option label="全部科室" value="" />
          <el-option
            v-for="s in scopeOptions"
            :key="s.id"
            :label="s.respDeptName"
            :value="s.id"
          />
        </el-select>
        <div class="ds-area">
          <el-tooltip
            v-for="ds in datasourceList"
            :key="ds.key"
            :content="`host: ${ds.host}${ds.configured ? ' · 已配置' : ' · 未配置'}`"
            placement="top"
          >
            <el-tag
              size="small"
              :type="pingResults[ds.key] ? (pingResults[ds.key].ok ? 'success' : 'danger') : 'info'"
              class="ds-tag"
              @click="isAdmin && onPing(ds)"
            >
              {{ ds.key }}
              <span v-if="pingResults[ds.key]">{{ pingResults[ds.key].ok ? ` ✓${pingResults[ds.key].elapsedMs}ms` : ' ✗' }}</span>
              <span v-else-if="!ds.configured">（未配置）</span>
            </el-tag>
          </el-tooltip>
          <span v-if="isAdmin" class="ds-hint">点击标签测试连接</span>
        </div>
        <el-button v-if="isAdmin" class="batch-btn" type="danger" plain @click="openBatchModal">
          覆盖评审数据
        </el-button>
      </div>
    </div>

    <div class="main-area">
      <!-- 左侧 SQL 列表 -->
      <div class="sql-list-panel eval-card">
        <div class="panel-title">SQL 列表（按科室）</div>
        <div v-if="!metricId" class="empty-hint">请先选择指标</div>
        <el-empty
          v-else-if="!sqlLoading && sqlList.length === 0"
          description="该范围无 SQL"
          :image-size="60"
        />
        <div v-loading="sqlLoading" class="sql-list">
          <div v-for="group in groupedSqls" :key="group.dept" class="dept-group">
            <div class="dept-name">{{ group.dept }}</div>
            <div
              v-for="item in group.items"
              :key="item.id"
              class="sql-item"
              :class="{ active: selected && selected.id === item.id, disabled: !item.isActive }"
              @click="onSelectSql(item)"
            >
              <div class="sql-item-row">
                <el-tag size="small" type="primary" disable-transitions>{{ roleLabel(item.sqlRole) }}</el-tag>
                <span class="sql-period">{{ item.periodStart }}~{{ item.periodEnd }}</span>
                <span class="sql-ds">{{ item.datasourceKey }}</span>
                <span class="sql-version">v{{ item.versionNo }}</span>
              </div>
              <div class="sql-item-row">
                <el-tag size="small" :type="item.isActive ? 'success' : 'info'" disable-transitions>
                  {{ item.isActive ? '启用' : '停用' }}
                </el-tag>
                <el-tag size="small" :type="verifyTag(item.lastVerifyStatus).tag" disable-transitions>
                  {{ verifyTag(item.lastVerifyStatus).label }}
                </el-tag>
                <el-tag v-if="item.dsFlag" size="small" type="warning" disable-transitions>数据源{{ item.dsFlag }}</el-tag>
                <el-tag v-if="item.periodFlag" size="small" type="warning" disable-transitions>时段{{ item.periodFlag }}</el-tag>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧编辑区 -->
      <div class="editor-panel eval-card">
        <template v-if="selected">
          <el-descriptions :column="4" size="small" border class="meta-desc">
            <el-descriptions-item label="来源">{{ selected.sourceRef || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最后修改人">{{ selected.lastModifyUser || '-' }}</el-descriptions-item>
            <el-descriptions-item label="修改原因">
              <span class="wrap-text">{{ selected.lastModifyReason || '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ selected.updateTime || '-' }}</el-descriptions-item>
          </el-descriptions>

          <SqlEditor v-model="sqlText" :readonly="!isAdmin" height="420px" />

          <!-- 校验结果 -->
          <div v-if="validateResult" class="validate-result">
            <el-alert
              v-if="validateResult.valid"
              type="success"
              :closable="false"
              title="校验通过"
              :description="validateResult.executableSql ? '已生成可执行 SQL（详见后端沙箱）' : ''"
            />
            <el-alert
              v-else
              type="error"
              :closable="false"
              title="校验未通过"
            >
              <div v-for="(e, i) in validateResult.errors || []" :key="'e' + i" class="err-line">{{ e }}</div>
            </el-alert>
            <el-alert
              v-if="(validateResult.warnings || []).length > 0"
              type="warning"
              :closable="false"
              title="警告"
            >
              <div v-for="(w, i) in validateResult.warnings" :key="'w' + i" class="err-line">{{ w }}</div>
            </el-alert>
          </div>

          <div class="btn-bar">
            <el-button size="small" :loading="validating" @click="onValidate">校验</el-button>
            <el-button v-if="isAdmin" size="small" type="primary" :loading="trial.loading" @click="onTrial">试跑前20行</el-button>
            <el-button v-if="isAdmin" size="small" type="success" @click="onSaveOpen">保存</el-button>
            <el-button v-if="isAdmin" size="small" type="warning" plain @click="onCreateOpen">新增 SQL</el-button>
            <el-button v-if="isAdmin" size="small" :type="selected.isActive ? 'danger' : 'success'" plain @click="onToggleOpen">
              {{ selected.isActive ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" @click="onHistoryOpen">历史版本</el-button>
          </div>
        </template>
        <div v-else class="empty-hint big">在左侧选择一条 SQL 查看与编辑</div>
      </div>
    </div>

    <!-- 试跑结果弹窗 -->
    <el-dialog v-model="trial.visible" title="试跑结果（前 20 行）" width="860px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="含患者信息，仅供核验，请勿截图外传"
        class="privacy-alert"
      />
      <p class="trial-meta">共 {{ trial.rowCount }} 行（返回前 {{ trial.rows.length }} 行） · 耗时 {{ trial.elapsedMs }} ms</p>
      <el-table :data="trialTableData" size="small" border max-height="420">
        <el-table-column
          v-for="(col, i) in trial.columns"
          :key="i"
          :label="col"
          :prop="'c' + i"
          min-width="140"
          show-overflow-tooltip
        />
      </el-table>
    </el-dialog>

    <!-- 保存弹窗 -->
    <el-dialog v-model="saveModal.visible" title="保存修改" width="480px">
      <el-form label-width="90px" size="small">
        <el-form-item label="修改类型" required>
          <el-select v-model="saveModal.changeType" placeholder="选择修改类型" style="width: 100%">
            <el-option v-for="t in CHANGE_TYPE_OPTIONS" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="修改原因" required>
          <el-input v-model="saveModal.changeReason" type="textarea" :rows="3" placeholder="至少 5 个字，写入历史版本留痕" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveModal.visible = false">取消</el-button>
        <el-button type="primary" :loading="saveModal.saving" @click="onSaveSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增 SQL 弹窗 -->
    <el-dialog v-model="createModal.visible" title="新增 SQL" width="720px" top="6vh">
      <el-form label-width="90px" size="small">
        <el-form-item label="SQL 角色" required>
          <el-select v-model="createModal.sqlRole" placeholder="选择角色" style="width: 200px">
            <el-option v-for="r in ROLE_OPTIONS" :key="r" :label="SQL_ROLE[r]" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="createModal.datasourceKey" placeholder="选择数据源" style="width: 200px">
            <el-option v-for="ds in datasourceList" :key="ds.key" :label="ds.key" :value="ds.key" :disabled="!ds.configured" />
          </el-select>
        </el-form-item>
        <el-form-item label="起止年月" required>
          <el-input v-model="createModal.periodStart" placeholder="如 202201" style="width: 140px" />
          <span class="period-sep">~</span>
          <el-input v-model="createModal.periodEnd" placeholder="如 202412" style="width: 140px" />
        </el-form-item>
        <el-form-item label="SQL" required>
          <div style="width: 100%">
            <SqlEditor v-model="createModal.sqlAnnotated" height="220px" />
          </div>
        </el-form-item>
        <el-form-item label="修改原因" required>
          <el-input v-model="createModal.changeReason" placeholder="写入历史留痕" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createModal.visible = false">取消</el-button>
        <el-button type="primary" :loading="createModal.saving" @click="onCreateSubmit">创建</el-button>
      </template>
    </el-dialog>

    <!-- 启用/停用弹窗 -->
    <el-dialog v-model="toggleModal.visible" :title="toggleModal.active ? '启用 SQL' : '停用 SQL'" width="440px">
      <p class="toggle-hint">确定要{{ toggleModal.active ? '启用' : '停用' }}该 SQL 吗？（版本号不递增，留痕于历史）</p>
      <el-input v-model="toggleModal.changeReason" type="textarea" :rows="2" placeholder="原因（必填）" />
      <template #footer>
        <el-button @click="toggleModal.visible = false">取消</el-button>
        <el-button type="primary" :loading="toggleModal.saving" @click="onToggleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 历史版本抽屉 -->
    <el-drawer v-model="history.visible" title="历史版本对比" size="70%">
      <div v-loading="history.loading">
        <el-timeline>
          <el-timeline-item
            v-for="his in history.list"
            :key="his.hisId"
            :timestamp="`${his.modifyTime} · ${his.modifyUserName}`"
            placement="top"
          >
            <div class="his-card" @click="onHisClick(his)">
              <b>v{{ his.versionNo }}</b>
              <el-tag size="small" class="his-tag" disable-transitions>{{ his.changeType }}</el-tag>
              <span class="his-reason">{{ his.changeReason }}</span>
              <span class="his-click-hint">点击对比</span>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-if="!history.loading && history.list.length === 0" description="无历史版本" :image-size="60" />
        <template v-if="history.diff">
          <el-divider>版本 v{{ history.diff.versionNo }} 修改前后对比</el-divider>
          <SqlDiffView :old-text="history.diff.old" :new-text="history.diff.new" />
        </template>
      </div>
    </el-drawer>

    <!-- 覆盖评审数据弹窗 -->
    <el-dialog v-model="batch.visible" title="覆盖评审数据（重算）" width="640px" @closed="stopPoll">
      <template v-if="!batch.batchId">
        <el-form label-width="100px" size="small">
          <el-form-item label="重算范围" required>
            <el-radio-group v-model="batch.scopeType">
              <el-radio value="scope" :disabled="scopeId === ''">当前科室实例</el-radio>
              <el-radio value="metric">当前指标全部科室</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="统计年份" required>
            <el-select v-model="batch.years" multiple style="width: 280px">
              <el-option v-for="y in YEAR_OPTIONS" :key="y" :label="y" :value="y" />
            </el-select>
          </el-form-item>
          <el-form-item label="版本说明">
            <el-input v-model="batch.versionTag" placeholder="选填，如：2026年11月口径修订" />
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          title="重算将生成新批次，旧结果自动保留在【历史版本】；已终审归档的数据不会被覆盖，需先解锁。"
        />
      </template>
      <template v-else>
        <p class="batch-meta">
          批次号 <b>{{ batch.batchNo }}</b>（#{{ batch.batchId }}）
          <el-tag v-if="batch.done" size="small" type="success" class="batch-tag">已完成</el-tag>
          <el-tag v-else size="small" type="primary" class="batch-tag">运行中（每 5 秒刷新）</el-tag>
        </p>
        <el-progress :percentage="batch.progress" :stroke-width="16" striped />
        <div v-if="batch.stats" class="batch-stats">
          <span>成功 {{ batch.stats.SUCCESS }}</span>
          <span class="fail-num">失败 {{ batch.stats.FAILED }}</span>
          <span>跳过 {{ batch.stats.SKIPPED }}</span>
          <span>待执行 {{ (batch.stats.PENDING || 0) + (batch.stats.RUNNING || 0) }}</span>
        </div>
        <template v-if="batch.done">
          <el-divider>失败子任务</el-divider>
          <el-table v-loading="batch.failedLoading" :data="batch.failedList" size="small" border max-height="260">
            <el-table-column prop="deptName" label="科室" min-width="130" />
            <el-table-column label="角色" width="100">
              <template #default="{ row }">{{ roleLabel(row.sqlRole) }}</template>
            </el-table-column>
            <el-table-column prop="errorMsg" label="错误信息" min-width="300" show-overflow-tooltip />
          </el-table>
          <el-empty v-if="!batch.failedLoading && batch.failedList.length === 0" description="无失败子任务" :image-size="48" />
        </template>
      </template>
      <template #footer>
        <template v-if="!batch.batchId">
          <el-button @click="batch.visible = false">取消</el-button>
          <el-button type="danger" @click="onBatchConfirm">确认重算</el-button>
        </template>
        <el-button v-else @click="batch.visible = false">{{ batch.done ? '关闭' : '后台运行' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.algo-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.top-row {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.ds-area {
  display: flex;
  gap: 6px;
  align-items: center;
}
.ds-tag {
  cursor: pointer;
}
.ds-hint {
  color: #909399;
  font-size: 12px;
}
.batch-btn {
  margin-left: auto;
}
.main-area {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.sql-list-panel {
  width: 360px;
  flex-shrink: 0;
}
.panel-title {
  font-weight: bold;
  color: #1f3a5f;
  margin-bottom: 8px;
}
.sql-list {
  max-height: calc(100vh - 320px);
  overflow: auto;
}
.dept-group {
  margin-bottom: 10px;
}
.dept-name {
  font-size: 12px;
  color: #909399;
  margin: 6px 0 4px;
}
.sql-item {
  border: 1px solid #e6e8eb;
  border-radius: 4px;
  padding: 8px;
  margin-bottom: 6px;
  cursor: pointer;
}
.sql-item:hover {
  border-color: #409eff;
}
.sql-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.sql-item.disabled {
  opacity: 0.55;
}
.sql-item-row {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 4px;
}
.sql-period,
.sql-ds,
.sql-version {
  font-size: 12px;
  color: #606266;
}
.editor-panel {
  flex: 1;
  min-width: 0;
}
.meta-desc {
  margin-bottom: 12px;
}
.validate-result {
  margin-top: 10px;
}
.err-line {
  font-size: 12px;
  line-height: 1.6;
}
.btn-bar {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
.empty-hint {
  color: #909399;
  text-align: center;
  padding: 40px 0;
}
.empty-hint.big {
  font-size: 15px;
  padding: 120px 0;
}
.privacy-alert {
  margin-bottom: 10px;
}
.trial-meta {
  color: #606266;
  font-size: 13px;
  margin: 0 0 8px;
}
.period-sep {
  margin: 0 8px;
}
.toggle-hint {
  margin: 0 0 10px;
}
.his-card {
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.his-card:hover {
  color: #409eff;
}
.his-reason {
  color: #606266;
  font-size: 13px;
}
.his-click-hint {
  color: #c0c4cc;
  font-size: 12px;
  margin-left: auto;
}
.batch-meta {
  margin: 0 0 10px;
}
.batch-tag {
  margin-left: 8px;
}
.batch-stats {
  margin-top: 10px;
  display: flex;
  gap: 16px;
  color: #606266;
  font-size: 13px;
}
.fail-num {
  color: #c0392b;
}
.wrap-text {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
