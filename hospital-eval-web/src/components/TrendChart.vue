<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

/**
 * 趋势组合图（TASK-15 步骤 1）：柱为分子/分母（左轴），折线为结果值（右轴）。
 * props data=[{year, num, den, val}]；窗口尺寸变化 resize，卸载 dispose。
 */
const props = defineProps({
  data: { type: Array, default: () => [] }
})

const el = ref(null)
let chart = null

function render() {
  if (!chart) return
  const list = props.data || []
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['分子', '分母', '结果值'], bottom: 0 },
    grid: { left: 48, right: 48, top: 28, bottom: 40 },
    xAxis: { type: 'category', data: list.map((d) => d.year) },
    yAxis: [
      { type: 'value', name: '分子/分母', scale: true },
      { type: 'value', name: '结果值', scale: true, axisLabel: { formatter: '{value}' } }
    ],
    series: [
      { name: '分子', type: 'bar', data: list.map((d) => d.num), itemStyle: { color: '#409eff' } },
      { name: '分母', type: 'bar', data: list.map((d) => d.den), itemStyle: { color: '#67c23a' } },
      {
        name: '结果值',
        type: 'line',
        yAxisIndex: 1,
        data: list.map((d) => d.val),
        smooth: true,
        itemStyle: { color: '#e6a23c' }
      }
    ]
  }, true)
}

function onResize() {
  chart && chart.resize()
}

watch(() => props.data, render, { deep: true })

onMounted(() => {
  chart = echarts.init(el.value)
  render()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<template>
  <div ref="el" class="trend-chart"></div>
</template>

<style scoped>
.trend-chart {
  width: 100%;
  height: 300px;
}
</style>
