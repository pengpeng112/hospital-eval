<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { dashboardOverview } from '@/api/dashboard'
import { workbenchOverview } from '@/api/report'
import { EXTRACT_MODE } from '@/utils/dict'

/**
 * 监测大屏（TASK-17 步骤 3）：深色驾驶舱，适配 1920×1080，窗口缩放图表 resize。
 * 全部数字来自 /api/dashboard/overview，接口为空显示"暂无数据"，无写死示例数字。
 */
const router = useRouter()

const year = ref(2025)
const data = ref(null)
const byMode = ref({})
const loading = ref(false)

const chartBar = ref(null)
const chartPie = ref(null)
const chartZero = ref(null)
const chartSpecialty = ref(null)
const chartTrend = ref(null)
let charts = []

const DARK_BG = '#0b1a33'
const TEXT = '#9fb6d9'
const AXIS = '#2c4a7c'

const cards = computed(() => (data.value && data.value.cards) || null)
const chapters = computed(() => (data.value && data.value.chapters) || [])
const specialties = computed(() => {
  const list = (data.value && data.value.specialties) || []
  // 按完成率（已填报/指标数）降序
  return list.slice().sort((a, b) => {
    const ra = a.metricCount > 0 ? a.filledCount / a.metricCount : 0
    const rb = b.metricCount > 0 ? b.filledCount / b.metricCount : 0
    return rb - ra
  })
})
const trend = computed(() => (data.value && data.value.trend) || [])

const fullNameOf = (s) => s.name || s.chapterCode

function initCharts() {
  const opts = { renderer: 'canvas' }
  charts = [chartBar, chartPie, chartZero, chartSpecialty, chartTrend]
    .map((el) => (el.value ? echarts.init(el.value, null, opts) : null))
  window.addEventListener('resize', resizeAll)
}

function resizeAll() {
  charts.forEach((c) => c && c.resize())
}

function disposeAll() {
  window.removeEventListener('resize', resizeAll)
  charts.forEach((c) => c && c.dispose())
  charts = []
}

function renderCharts() {
  const [bar, pie, zero, specialty, trendChart] = charts
  const common = {
    backgroundColor: 'transparent',
    textStyle: { color: TEXT },
    tooltip: { trigger: 'axis' }
  }

  // 左：章节完成情况（指标数 vs 已填报数）
  if (bar) {
    const list = chapters.value
    bar.setOption({
      ...common,
      title: { text: '章节完成情况', textStyle: { color: TEXT, fontSize: 14 }, left: 8, top: 4 },
      legend: { data: ['指标数', '已填报'], textStyle: { color: TEXT }, bottom: 0 },
      grid: { left: 44, right: 16, top: 36, bottom: 30 },
      xAxis: { type: 'category', data: list.map((c) => c.chapterCode), axisLine: { lineStyle: { color: AXIS } } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#1a3055' } } },
      series: [
        { name: '指标数', type: 'bar', data: list.map((c) => c.metricCount), itemStyle: { color: '#2c6dd2' } },
        { name: '已填报', type: 'bar', data: list.map((c) => c.filledCount), itemStyle: { color: '#27c2a5' } }
      ]
    }, true)
  }

  // 中：信息化提取方式饼图（复用 workbenchOverview 的 byExtractMode）
  if (pie) {
    const pieData = Object.keys(EXTRACT_MODE).map((k) => ({
      name: EXTRACT_MODE[k].label,
      value: byMode.value[k] || 0,
      itemStyle: { color: { SYS: '#27c2a5', MANUAL: '#e6a23c', HYBRID: '#409eff', PROV: '#6b7a99' }[k] }
    }))
    pie.setOption({
      backgroundColor: 'transparent',
      textStyle: { color: TEXT },
      tooltip: { trigger: 'item' },
      title: { text: '信息化提取方式', textStyle: { color: TEXT, fontSize: 14 }, left: 8, top: 4 },
      legend: { bottom: 0, textStyle: { color: TEXT }, itemWidth: 12, itemHeight: 12 },
      series: [{
        type: 'pie',
        radius: ['40%', '65%'],
        center: ['50%', '48%'],
        label: { color: TEXT, formatter: '{b}: {c}' },
        data: pieData
      }]
    }, true)
  }

  // 右：零值指标数柱状
  if (zero) {
    const list = chapters.value
    zero.setOption({
      ...common,
      title: { text: '零值指标数（分子或分母为 0）', textStyle: { color: TEXT, fontSize: 14 }, left: 8, top: 4 },
      grid: { left: 44, right: 16, top: 36, bottom: 30 },
      xAxis: { type: 'category', data: list.map((c) => c.chapterCode), axisLine: { lineStyle: { color: AXIS } } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#1a3055' } } },
      series: [{
        type: 'bar',
        data: list.map((c) => c.zeroCount),
        itemStyle: { color: '#f56c6c' },
        label: { show: true, position: 'top', color: TEXT }
      }]
    }, true)
  }

  // 下左：重点专业横向条形（按完成率排序）
  if (specialty) {
    const list = specialties.value
    specialty.setOption({
      backgroundColor: 'transparent',
      textStyle: { color: TEXT },
      tooltip: {
        trigger: 'item',
        formatter: (p) => {
          const s = list[p.dataIndex]
          return `${fullNameOf(s)}<br/>已填报 ${s.filledCount} / ${s.metricCount}（${p.value}%）`
        }
      },
      title: { text: '重点专业完成情况（点击条形查看明细）', textStyle: { color: TEXT, fontSize: 14 }, left: 8, top: 4 },
      grid: { left: 8, right: 60, top: 36, bottom: 16, containLabel: true },
      xAxis: { type: 'value', max: 100, axisLabel: { color: TEXT, formatter: '{value}%' }, splitLine: { lineStyle: { color: '#1a3055' } } },
      yAxis: {
        type: 'category',
        data: list.map((s) => s.chapterCode),
        axisLabel: { color: TEXT, fontSize: 11 },
        axisLine: { lineStyle: { color: AXIS } }
      },
      series: [{
        type: 'bar',
        data: list.map((s) => (s.metricCount > 0 ? Math.round(s.filledCount * 1000 / s.metricCount) / 10 : 0)),
        itemStyle: { color: '#409eff' },
        barMaxWidth: 14,
        label: { show: true, position: 'right', color: TEXT, formatter: '{c}%' }
      }]
    }, true)
    specialty.off('click')
    specialty.on('click', (p) => {
      const s = list[p.dataIndex]
      if (s) {
        router.push({ path: '/summary/current', query: { chapterCode: s.chapterCode } })
      }
    })
  }

  // 下右：年度趋势折线
  if (trendChart) {
    const t = trend.value
    trendChart.setOption({
      ...common,
      title: { text: '年度填报趋势', textStyle: { color: TEXT, fontSize: 14 }, left: 8, top: 4 },
      grid: { left: 44, right: 24, top: 36, bottom: 30 },
      xAxis: { type: 'category', data: t.map((x) => x.year), axisLine: { lineStyle: { color: AXIS } } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: '#1a3055' } } },
      series: [{
        name: '已填报指标数',
        type: 'line',
        data: t.map((x) => x.filledCount),
        smooth: true,
        itemStyle: { color: '#27c2a5' },
        areaStyle: { color: 'rgba(39,194,165,0.15)' },
        label: { show: true, color: TEXT }
      }]
    }, true)
  }
}

async function load() {
  loading.value = true
  try {
    data.value = await dashboardOverview({ year: year.value })
    const wb = await workbenchOverview({ year: year.value })
    byMode.value = (wb && wb.byExtractMode) || {}
    renderCharts()
  } finally {
    loading.value = false
  }
}

function fullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen()
  } else {
    document.documentElement.requestFullscreen()
  }
}

watch(year, load)
onMounted(() => {
  initCharts()
  load()
})
onBeforeUnmount(disposeAll)
</script>

<template>
  <div class="dash" v-loading="loading" element-loading-background="rgba(11,26,51,0.8)">
    <div class="dash-head">
      <span class="dash-title">医院等级评审指标监测大屏</span>
      <el-select v-model="year" size="small" style="width: 100px" class="dash-year">
        <el-option v-for="y in [2022, 2023, 2024, 2025]" :key="y" :label="`${y} 年`" :value="y" />
      </el-select>
      <el-button size="small" class="dash-fs" @click="fullscreen">全屏</el-button>
    </div>

    <!-- 第一行：4 个指标卡 -->
    <div class="row cards">
      <div class="card">
        <div class="num">{{ cards ? cards.metricTotal : '—' }}</div>
        <div class="label">适用指标数</div>
      </div>
      <div class="card">
        <div class="num teal">{{ cards ? cards.sysRate : '—' }}<span v-if="cards" class="unit">%</span></div>
        <div class="label">信息化提取率</div>
      </div>
      <div class="card">
        <div class="num orange">{{ cards ? cards.manualRate : '—' }}<span v-if="cards" class="unit">%</span></div>
        <div class="label">人工填报占比</div>
      </div>
      <div class="card">
        <div class="num">{{ cards ? cards.archivedRate : '—' }}<span v-if="cards" class="unit">%</span></div>
        <div class="label">终审归档率</div>
      </div>
    </div>

    <!-- 第二行：章节柱状 / 提取方式饼 / 零值柱状 -->
    <div class="row row-3">
      <div class="panel"><div ref="chartBar" class="chart"></div></div>
      <div class="panel"><div ref="chartPie" class="chart"></div></div>
      <div class="panel"><div ref="chartZero" class="chart"></div></div>
    </div>

    <!-- 第三行：专业横向条形 + 趋势折线 -->
    <div class="row row-2">
      <div class="panel">
        <div ref="chartSpecialty" class="chart"></div>
        <div v-if="specialties.length === 0" class="empty">暂无数据</div>
      </div>
      <div class="panel">
        <div ref="chartTrend" class="chart"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dash {
  min-height: calc(100vh - 120px);
  background: #0b1a33;
  border-radius: 4px;
  padding: 12px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dash-head {
  display: flex;
  align-items: center;
  gap: 14px;
}
.dash-title {
  font-size: 20px;
  font-weight: bold;
  color: #e8f0ff;
  letter-spacing: 2px;
  margin-right: auto;
}
.dash-year :deep(.el-input__wrapper) {
  background: #12294d;
  box-shadow: 0 0 0 1px #2c4a7c inset;
}
.dash-year :deep(.el-input__inner) {
  color: #cfe0ff;
}
.row {
  display: flex;
  gap: 10px;
}
.cards .card {
  flex: 1;
  background: #12294d;
  border: 1px solid #1a3055;
  border-radius: 4px;
  text-align: center;
  padding: 18px 8px;
}
.num {
  font-size: 30px;
  font-weight: bold;
  color: #e8f0ff;
}
.num.teal {
  color: #27c2a5;
}
.num.orange {
  color: #e6a23c;
}
.unit {
  font-size: 14px;
  margin-left: 2px;
}
.label {
  color: #9fb6d9;
  font-size: 12px;
  margin-top: 6px;
}
.row-3 .panel {
  flex: 1;
  min-width: 0;
}
.row-2 .panel {
  flex: 1;
  min-width: 0;
}
.panel {
  position: relative;
  background: #12294d;
  border: 1px solid #1a3055;
  border-radius: 4px;
}
.chart {
  height: 300px;
  width: 100%;
}
.row-2 .chart {
  height: 320px;
}
.empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7a99;
  font-size: 13px;
}
</style>
