<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  saveManualValue, listHybridPool, hybridSelect,
  uploadEvidence, listEvidence, getEvidencePreviewUrl, deleteEvidence
} from '@/api/report'
import { useUserStore } from '@/stores/user'
import EmrRecordDrawer from '@/views/summary/EmrRecordDrawer.vue'

/**
 * 填报抽屉（TASK-16 步骤 2，宽 75%）：按 extractMode 显示 Tab。
 * ①数值填报（MANUAL 或分子分母全空补录）②分母病例池勾选（listHybridPool 400 则隐藏）
 * ③佐证材料（7 固定分类 + 拖拽上传 + 预览降级）。
 * 状态流转一律调后端接口，前端不自行改状态。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null },
  year: { type: [Number, String], default: 2025 }
})

const emit = defineEmits(['update:visible', 'refresh'])

const store = useUserStore()
const isAdmin = computed(() => store.hasRole('ADMIN'))

const EVIDENCE_EXT_WHITELIST = ['doc', 'docx', 'xls', 'xlsx', 'pdf', 'jpg', 'jpeg', 'png']
const FOLDERS = ['原始登记台账', '制度文件', '培训记录', '督导检查', '会议纪要', '持续改进(PDCA)', '其他']
const QUAL_OPTIONS = ['是', '否', '符合', '部分符合', '不符合']

const activeTab = ref('value')
const editable = computed(() => !props.row || ['DRAFT', 'L2_REJECT'].includes(props.row.auditStatus))

// ---------------- Tab①：数值填报 ----------------
const form = reactive({ numeratorVal: null, denominatorVal: null, qualitativeResult: '', remark: '' })
const savingValue = ref(false)

/** 是否显示数值填报 Tab：MANUAL，或任何提取方式下分子分母为空需补录。 */
const showValueTab = computed(() => {
  if (!props.row) return false
  return props.row.extractMode === 'MANUAL'
    || (props.row.num == null && props.row.den == null)
})

function loadValueForm() {
  form.numeratorVal = props.row && props.row.num != null ? Number(props.row.num) : null
  form.denominatorVal = props.row && props.row.den != null ? Number(props.row.den) : null
  form.qualitativeResult = ''
  form.remark = ''
}

async function onSaveValue() {
  if (!props.row) return
  savingValue.value = true
  try {
    await saveManualValue({
      metricId: props.row.metricId,
      scopeId: props.row.scopeId,
      year: props.year,
      numeratorVal: form.numeratorVal,
      denominatorVal: form.denominatorVal,
      qualitativeResult: form.qualitativeResult || null,
      remark: form.remark || null
    })
    ElMessage.success('保存成功（已重算全院行）')
    emit('refresh')
  } finally {
    savingValue.value = false
  }
}

// ---------------- Tab②：分母病例池勾选 ----------------
const pool = reactive({
  show: false, checked: false, loading: false, columns: [], list: [], total: 0,
  page: 1, size: 50, keyword: '', denCount: 0, numerator: null
})
const poolSelect = reactive({ visible: false, row: null, selected: false, remark: '' })
const emr = reactive({ visible: false, patientId: '', visitId: '', inpNo: '' })

function poolCellValue(row, prop) {
  if (prop.startsWith('ext.')) {
    const key = prop.slice(4)
    const ext = row.ext || {}
    return ext[key] == null ? '' : String(ext[key])
  }
  const v = row[prop]
  return v == null ? '' : String(v)
}

async function detectPool() {
  pool.show = false
  if (!props.row) return
  try {
    const data = await listHybridPool({
      metricId: props.row.metricId, scopeId: props.row.scopeId, year: props.year, page: 1, size: 1
    })
    pool.show = true // 探测成功 → 显示 Tab
    pool.columns = data.columns || []
    pool.total = data.total || 0
  } catch (e) {
    pool.show = false // 400 等错误 → 不显示此 Tab
  }
}

async function loadPool() {
  if (!props.row) return
  pool.loading = true
  try {
    const params = {
      metricId: props.row.metricId, scopeId: props.row.scopeId, year: props.year,
      page: pool.page, size: pool.size
    }
    if (pool.keyword) params.keyword = pool.keyword
    const data = await listHybridPool(params)
    pool.list = data.list || []
    pool.total = data.total || 0
    pool.denCount = data.total || 0
  } finally {
    pool.loading = false
  }
}

function onPoolSearch() {
  pool.page = 1
  loadPool()
}

function onPoolCheckChange(row, checked) {
  poolSelect.row = row
  poolSelect.selected = checked
  poolSelect.remark = checked ? (row.selectRemark || '') : (row.selectRemark || '')
  poolSelect.visible = true
}

async function onPoolSelectConfirm() {
  const { row, selected } = poolSelect
  if (selected && (!poolSelect.remark || !poolSelect.remark.trim())) {
    ElMessage.warning('勾选为分子时判定依据必填')
    return
  }
  try {
    const data = await hybridSelect({
      metricId: props.row.metricId,
      scopeId: props.row.scopeId,
      year: props.year,
      detailIds: [row.id],
      selected,
      remark: selected ? poolSelect.remark.trim() : null
    })
    // 分子以后端 hybridSelect 返回值为准，前端不计算
    pool.numerator = data.numerator
    ElMessage.success(selected ? `已勾选，当前分子 ${data.numerator}` : '已取消勾选')
    poolSelect.visible = false
    loadPool()
    emit('refresh')
  } catch (e) {
    // 失败时刷新池恢复复选框真实状态
    loadPool()
  }
}

function onPoolSelectCancel() {
  // 取消弹窗 → 还原复选框显示
  loadPool()
  poolSelect.visible = false
}

function openEmr(row) {
  emr.patientId = row.patientId || ''
  emr.visitId = row.visitId || ''
  emr.inpNo = row.inpNo || ''
  emr.visible = true
}

// ---------------- Tab③：佐证材料 ----------------
const evidence = reactive({
  list: [], loading: false, currentFolder: FOLDERS[0], uploading: false,
  preview: { url: '', rawUrl: '', fileName: '', fileExt: '', degraded: false, isImage: false }
})

async function loadEvidence() {
  if (!props.row) return
  evidence.loading = true
  try {
    evidence.list = (await listEvidence({
      metricId: props.row.metricId, scopeId: props.row.scopeId, statPeriod: String(props.year)
    })) || []
  } finally {
    evidence.loading = false
  }
}

function countOf(folder) {
  return evidence.list.filter((e) => (e.folderCategory || '其他') === folder).length
}

function filesOf(folder) {
  return evidence.list.filter((e) => (e.folderCategory || '其他') === folder)
}

function extWhitelistOk(name) {
  const dot = name.lastIndexOf('.')
  if (dot < 0 || dot === name.length - 1) return false
  return EVIDENCE_EXT_WHITELIST.includes(name.slice(dot + 1).toLowerCase())
}

function beforeUpload(file) {
  if (!extWhitelistOk(file.name)) {
    ElMessage.error('不支持的文件类型（仅 doc docx xls xlsx pdf jpg jpeg png）')
    return false
  }
  if (file.size > 100 * 1024 * 1024) {
    ElMessage.error('文件超过 100MB 上限')
    return false
  }
  return true
}

async function onUpload(option) {
  evidence.uploading = true
  try {
    await uploadEvidence(option.file, {
      metricId: props.row.metricId,
      scopeId: props.row.scopeId,
      statPeriod: String(props.year),
      folderCategory: evidence.currentFolder
    })
    ElMessage.success('上传成功')
    loadEvidence()
  } finally {
    evidence.uploading = false
  }
}

async function onPreviewFile(f) {
  evidence.preview = { url: '', rawUrl: '', fileName: f.fileName, fileExt: (f.fileExt || '').toLowerCase(), degraded: false, isImage: false }
  try {
    const data = await getEvidencePreviewUrl(f.id)
    evidence.preview.url = data.url || ''
    evidence.preview.rawUrl = data.rawUrl || ''
  } catch (e) {
    // 预览地址接口失败（如 kkFileView 未配置）→ 降级
    evidence.preview.url = ''
  }
  if (!evidence.preview.url) {
    if (['pdf'].includes(evidence.preview.fileExt)) {
      evidence.preview.degraded = true
      evidence.preview.rawUrl = evidence.preview.rawUrl || ''
    } else if (['jpg', 'jpeg', 'png'].includes(evidence.preview.fileExt)) {
      evidence.preview.degraded = true
      evidence.preview.isImage = true
    } else {
      ElMessage.warning('预览服务不可用，请下载查看')
    }
  }
}

async function onDeleteEvidence(f) {
  try {
    await ElMessageBox.confirm(`确认删除附件「${f.fileName}」？（物理文件移入回收目录）`, '删除附件', { type: 'warning' })
  } catch (e) {
    return
  }
  await deleteEvidence(f.id)
  ElMessage.success('已删除')
  if (evidence.preview.fileName === f.fileName) {
    evidence.preview = { url: '', rawUrl: '', fileName: '', fileExt: '', degraded: false, isImage: false }
  }
  loadEvidence()
}

// ---------------- 打开/关闭 ----------------
watch(() => props.visible, (v) => {
  if (v && props.row) {
    loadValueForm()
    activeTab.value = showValueTab.value ? 'value' : 'evidence'
    evidence.preview = { url: '', rawUrl: '', fileName: '', fileExt: '', degraded: false, isImage: false }
    detectPool().then(() => {
      if (pool.show) loadPool()
    })
    loadEvidence()
  }
})
</script>

<template>
  <el-drawer
    :model-value="visible"
    size="75%"
    :title="row ? `填报：${row.metricName || ''} · ${row.deptName || ''}` : '填报'"
    @update:model-value="emit('update:visible', $event)"
  >
    <div v-if="row" class="drawer-body">
      <el-alert
        v-if="row.extractMode === 'MANUAL'"
        type="error"
        :closable="false"
        show-icon
        title="纯手工指标提交前须上传原始登记台账或扫描件（省卫健委：杜绝无原始数据支撑）"
        class="manual-tip"
      />

      <el-tabs v-model="activeTab">
        <!-- Tab① 数值填报 -->
        <el-tab-pane v-if="showValueTab" label="数值填报" name="value">
          <el-alert
            v-if="!editable"
            type="info"
            :closable="false"
            title="当前状态不可修改"
            class="tab-alert"
          />
          <el-form :model="form" label-width="100px" size="small" :disabled="!editable" style="max-width: 480px">
            <el-form-item label="分子">
              <el-input-number v-model="form.numeratorVal" :controls="false" :precision="4" style="width: 200px" />
            </el-form-item>
            <el-form-item label="分母">
              <el-input-number v-model="form.denominatorVal" :controls="false" :precision="4" style="width: 200px" />
            </el-form-item>
            <el-form-item label="定性结论">
              <el-select v-model="form.qualitativeResult" clearable placeholder="可空" style="width: 200px">
                <el-option v-for="q in QUAL_OPTIONS" :key="q" :label="q" :value="q" />
              </el-select>
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingValue" @click="onSaveValue">保存</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- Tab② 分母病例池勾选 -->
        <el-tab-pane v-if="pool.show" label="分母病例池勾选" name="pool">
          <el-alert
            v-if="!editable"
            type="info"
            :closable="false"
            title="当前状态不可修改"
            class="tab-alert"
          />
          <div class="pool-head">
            <span>分母 <b>{{ pool.denCount }}</b> 人次 / 已勾选分子 <b>{{ pool.numerator != null ? pool.numerator : '—' }}</b> 人次</span>
            <el-input
              v-model="pool.keyword"
              size="small"
              clearable
              placeholder="关键字"
              style="width: 200px"
              @keyup.enter="onPoolSearch"
              @clear="onPoolSearch"
            />
            <el-button size="small" type="primary" @click="onPoolSearch">查询</el-button>
          </div>
          <el-table v-loading="pool.loading" :data="pool.list" size="small" border max-height="420">
            <el-table-column
              v-for="col in pool.columns"
              :key="col.prop"
              :label="col.label"
              min-width="120"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <el-link
                  v-if="col.prop === 'inpNo' && row.inpNo"
                  type="primary"
                  :underline="false"
                  @click="openEmr(row)"
                >{{ row.inpNo }}</el-link>
                <span v-else>{{ poolCellValue(row, col.prop) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="已选为分子" width="90" align="center">
              <template #default="{ row }">
                <el-switch
                  :model-value="!!row.manualSelected"
                  :disabled="!editable"
                  @change="(v) => onPoolCheckChange(row, v)"
                />
              </template>
            </el-table-column>
            <el-table-column label="判定依据" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.selectRemark || '-' }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="pool.page"
            class="pager"
            background
            layout="total, prev, pager, next"
            :total="pool.total"
            :page-size="pool.size"
            @current-change="loadPool"
          />

          <!-- 判定依据小窗 -->
          <el-dialog v-model="poolSelect.visible" width="440px" append-to-body
            :title="poolSelect.selected ? '勾选为分子 · 填写判定依据' : '取消勾选'">
            <el-input
              v-model="poolSelect.remark"
              type="textarea"
              :rows="3"
              :placeholder="poolSelect.selected ? '判定依据（必填）' : '可补充说明（选填）'"
            />
            <template #footer>
              <el-button @click="onPoolSelectCancel">取消</el-button>
              <el-button type="primary" @click="onPoolSelectConfirm">确定</el-button>
            </template>
          </el-dialog>
        </el-tab-pane>

        <!-- Tab③ 佐证材料 -->
        <el-tab-pane label="佐证材料" name="evidence">
          <div class="ev-split">
            <!-- 左：分类目录 -->
            <div class="ev-folders">
              <div
                v-for="folder in FOLDERS"
                :key="folder"
                class="ev-folder"
                :class="{ active: evidence.currentFolder === folder }"
                @click="evidence.currentFolder = folder"
              >
                {{ folder }}
                <span class="ev-count">{{ countOf(folder) }}</span>
              </div>
            </div>

            <!-- 中：文件列表 + 上传 -->
            <div class="ev-files">
              <el-upload
                drag
                multiple
                :show-file-list="false"
                :before-upload="beforeUpload"
                :http-request="onUpload"
                :disabled="!editable"
                class="ev-upload"
              >
                <div class="ev-upload-text">
                  拖拽文件到此处或<em>点击上传</em>
                  <div class="ev-upload-hint">上传至「{{ evidence.currentFolder }}」· 支持 doc/docx/xls/xlsx/pdf/jpg/jpeg/png · ≤100MB</div>
                </div>
              </el-upload>
              <div v-loading="evidence.loading" class="ev-file-list">
                <div
                  v-for="f in filesOf(evidence.currentFolder)"
                  :key="f.id"
                  class="ev-file"
                  :class="{ active: evidence.preview.fileName === f.fileName }"
                  @click="onPreviewFile(f)"
                >
                  <span class="ev-file-name">{{ f.fileName }}</span>
                  <span class="ev-file-meta">{{ f.uploadUserName }} · {{ f.uploadTime }}</span>
                  <el-button
                    v-if="isAdmin"
                    link
                    type="danger"
                    size="small"
                    @click.stop="onDeleteEvidence(f)"
                  >删除</el-button>
                </div>
                <el-empty
                  v-if="!evidence.loading && filesOf(evidence.currentFolder).length === 0"
                  description="该分类暂无文件"
                  :image-size="48"
                />
              </div>
            </div>

            <!-- 右：预览区（55%） -->
            <div class="ev-preview">
              <template v-if="evidence.preview.fileName">
                <div class="ev-preview-title">{{ evidence.preview.fileName }}</div>
                <iframe
                  v-if="evidence.preview.url && !evidence.preview.degraded"
                  :src="evidence.preview.url"
                  class="ev-frame"
                  frameborder="0"
                ></iframe>
                <img
                  v-else-if="evidence.preview.isImage && evidence.preview.rawUrl"
                  :src="evidence.preview.rawUrl"
                  class="ev-frame"
                  alt="附件预览"
                />
                <iframe
                  v-else-if="evidence.preview.degraded && evidence.preview.rawUrl"
                  :src="evidence.preview.rawUrl"
                  class="ev-frame"
                  frameborder="0"
                ></iframe>
                <div v-else class="ev-preview-empty">预览服务不可用，请下载查看</div>
              </template>
              <el-empty v-else description="点击左侧文件预览" :image-size="60" />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <EmrRecordDrawer
        v-model:visible="emr.visible"
        :patient-id="emr.patientId"
        :visit-id="emr.visitId"
        :inp-no="emr.inpNo"
        :metric-id="row && row.metricId"
      />
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-body {
  height: 100%;
}
.manual-tip {
  margin-bottom: 12px;
}
.tab-alert {
  margin-bottom: 10px;
}
.pool-head {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
  color: #606266;
  font-size: 13px;
}
.pager {
  margin-top: 10px;
  justify-content: flex-end;
}
.ev-split {
  display: flex;
  gap: 10px;
  height: calc(100vh - 320px);
  min-height: 360px;
}
.ev-folders {
  width: 170px;
  flex-shrink: 0;
  border: 1px solid #e6e8eb;
  border-radius: 4px;
  padding: 6px;
}
.ev-folder {
  padding: 8px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  justify-content: space-between;
}
.ev-folder:hover {
  background: #f5f7fa;
}
.ev-folder.active {
  background: #ecf5ff;
  color: #409eff;
}
.ev-count {
  color: #909399;
}
.ev-files {
  width: 30%;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
.ev-upload-text {
  font-size: 13px;
  color: #606266;
}
.ev-upload-hint {
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
}
.ev-file-list {
  flex: 1;
  overflow: auto;
  border: 1px solid #e6e8eb;
  border-radius: 4px;
  padding: 4px;
}
.ev-file {
  padding: 8px;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ev-file:hover {
  background: #f5f7fa;
}
.ev-file.active {
  background: #ecf5ff;
}
.ev-file-name {
  font-size: 13px;
  word-break: break-all;
}
.ev-file-meta {
  font-size: 11px;
  color: #909399;
}
.ev-preview {
  flex: 1;
  min-width: 0;
  border: 1px solid #e6e8eb;
  border-radius: 4px;
  padding: 8px;
  display: flex;
  flex-direction: column;
}
.ev-preview-title {
  font-size: 13px;
  font-weight: bold;
  color: #1f3a5f;
  margin-bottom: 6px;
  word-break: break-all;
}
.ev-frame {
  flex: 1;
  width: 100%;
  border: none;
  background: #fff;
}
.ev-preview-empty {
  margin: auto;
  color: #909399;
}
</style>
