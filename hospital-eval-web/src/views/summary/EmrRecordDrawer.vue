<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get360Url, listEmrDocuments, getEmrDocumentContent, getMetricPanel } from '@/api/result'
import { highlight } from '@/utils/escape'

/**
 * 病历抽屉（TASK-15 步骤 5）：360 视图跳转、文书列表、正文高亮渲染。
 * v-html 只绑定 highlight()（先转义后包裹关键词）的结果，禁止渲染原始正文。
 * 关键词默认取指标本院统计口径中引号内的词（最多 5 个），可自行增删。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  patientId: { type: String, default: '' },
  visitId: { type: String, default: '' },
  inpNo: { type: String, default: '' },
  metricId: { type: Number, default: null }
})

const emit = defineEmits(['update:visible'])

const url360 = ref('')
const url360Tip = ref('')
const docs = ref([])
const warnings = ref([])
const docLoading = ref(false)
const current = ref(null) // {sys, docId}
const content = ref('')
const contentTitle = ref('')
const contentLoading = ref(false)
const keywords = ref([])
const keywordInput = ref('')

function close() {
  emit('update:visible', false)
}

/** 从口径文本提取引号内关键词（最多 5 个）。 */
function extractKeywords(caliber) {
  if (!caliber) return []
  const re = /[“"](.+?)[”"]/g
  const out = []
  let m
  while ((m = re.exec(caliber)) !== null) {
    if (m[1].trim()) out.push(m[1].trim())
    if (out.length >= 5) break
  }
  return out
}

async function loadAll() {
  url360.value = ''
  url360Tip.value = ''
  docs.value = []
  warnings.value = []
  current.value = null
  content.value = ''
  contentTitle.value = ''
  keywords.value = []
  keywordInput.value = ''
  if (!props.patientId || !props.visitId) return

  // 360 视图地址
  try {
    const d = await get360Url({ patientId: props.patientId, visitId: props.visitId, inpNo: props.inpNo })
    url360.value = d.url || ''
    url360Tip.value = d.url ? '' : '未配置360视图地址'
  } catch (e) {
    url360Tip.value = '未配置360视图地址'
  }

  // 关键词默认值：指标本院统计口径引号内词
  if (props.metricId) {
    try {
      const panel = await getMetricPanel(props.metricId)
      keywords.value = extractKeywords(panel.dict && panel.dict.hospCaliberDesc)
    } catch (e) {
      keywords.value = []
    }
  }

  // 文书列表
  docLoading.value = true
  try {
    const d = await listEmrDocuments({ patientId: props.patientId, visitId: props.visitId, inpNo: props.inpNo })
    docs.value = (d.documents || []).slice().sort((a, b) => String(b.docTime || '').localeCompare(String(a.docTime || '')))
    warnings.value = d.warnings || []
  } finally {
    docLoading.value = false
  }
}

async function openDoc(doc) {
  current.value = { sys: doc.sys, docId: doc.docId }
  contentTitle.value = doc.title || ''
  contentLoading.value = true
  try {
    const d = await getEmrDocumentContent({
      sys: doc.sys,
      docId: doc.docId,
      patientId: props.patientId,
      visitId: props.visitId,
      inpNo: props.inpNo
    })
    content.value = d.content || ''
  } finally {
    contentLoading.value = false
  }
}

function open360() {
  if (!url360.value) return
  window.open(url360.value, '_blank')
}

function addKeyword() {
  const v = keywordInput.value.trim()
  if (v && !keywords.value.includes(v)) {
    keywords.value.push(v)
  }
  keywordInput.value = ''
}

function removeKeyword(kw) {
  keywords.value = keywords.value.filter((k) => k !== kw)
}

watch(() => props.visible, (v) => {
  if (v) loadAll()
})
</script>

<template>
  <el-drawer
    :model-value="visible"
    size="72%"
    :title="`病历调阅（${inpNo || patientId}）`"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="emr-body">
      <div class="emr-toolbar">
        <el-button type="primary" size="small" :disabled="!url360" @click="open360">打开院内360视图</el-button>
        <span v-if="url360Tip" class="muted">{{ url360Tip }}</span>
        <div class="kw-area">
          <span class="muted">高亮关键词（回车添加）：</span>
          <el-input
            v-model="keywordInput"
            size="small"
            style="width: 180px"
            placeholder="输入关键词"
            @keyup.enter="addKeyword"
          />
          <el-tag
            v-for="kw in keywords"
            :key="kw"
            size="small"
            closable
            class="kw-tag"
            @close="removeKeyword(kw)"
          >{{ kw }}</el-tag>
        </div>
      </div>

      <el-alert
        v-for="(w, i) in warnings"
        :key="i"
        type="warning"
        :closable="false"
        :title="w"
        class="emr-warn"
      />

      <div class="emr-split">
        <!-- 左：文书列表 -->
        <div class="doc-list" v-loading="docLoading">
          <div
            v-for="doc in docs"
            :key="doc.sys + '-' + doc.docId"
            class="doc-item"
            :class="{ active: current && current.sys === doc.sys && current.docId === doc.docId }"
            @click="openDoc(doc)"
          >
            <el-tag size="small" :type="doc.sys === 'MTL' ? 'primary' : 'success'" disable-transitions>
              {{ doc.sys }}
            </el-tag>
            <span class="doc-title">{{ doc.title }}</span>
            <span class="doc-time">{{ doc.docTime }}</span>
          </div>
          <el-empty v-if="!docLoading && docs.length === 0" description="无文书" :image-size="60" />
        </div>

        <!-- 右：正文（v-html 仅绑定 highlight() 转义结果） -->
        <div class="doc-content" v-loading="contentLoading">
          <div v-if="contentTitle" class="content-title">{{ contentTitle }}</div>
          <!-- eslint-disable-next-line vue/no-v-html —— 正文已经 highlight() 先转义后高亮，安全 -->
          <div v-if="current" class="emr-content" v-html="highlight(content, keywords)"></div>
          <el-empty v-else description="点击左侧文书查看原文" :image-size="60" />
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.emr-body {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.emr-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.kw-area {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.kw-tag {
  cursor: default;
}
.emr-warn {
  margin-bottom: 8px;
}
.emr-split {
  flex: 1;
  display: flex;
  gap: 10px;
  min-height: 0;
}
.doc-list {
  width: 300px;
  flex-shrink: 0;
  overflow: auto;
  border: 1px solid #e6e8eb;
  border-radius: 4px;
  padding: 4px;
}
.doc-item {
  padding: 8px;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.doc-item:hover {
  background: #f5f7fa;
}
.doc-item.active {
  background: #ecf5ff;
}
.doc-title {
  font-size: 13px;
  word-break: break-all;
}
.doc-time {
  font-size: 12px;
  color: #909399;
}
.doc-content {
  flex: 1;
  min-width: 0;
  overflow: auto;
  border: 1px solid #e6e8eb;
  border-radius: 4px;
  padding: 12px;
}
.content-title {
  font-weight: bold;
  margin-bottom: 8px;
  color: #1f3a5f;
}
.emr-content {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.7;
}
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
