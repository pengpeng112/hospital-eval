<script setup>
import { computed } from 'vue'
import { extractModeOf } from '@/utils/dict'

/**
 * 提取方式彩色标签（04 §13）：SYS 绿 / MANUAL 橙 / HYBRID 蓝 / PROV 灰。
 * applicable === false 时优先显示灰色"不适用"（TASK-13 步骤 1）。
 */
const props = defineProps({
  mode: { type: String, default: '' },
  applicable: { type: Boolean, default: null }
})

const item = computed(() => {
  if (props.applicable === false) {
    return { label: '不适用', tag: 'info' }
  }
  return extractModeOf(props.mode)
})
</script>

<template>
  <el-tag :type="item.tag" size="small" disable-transitions>{{ item.label }}</el-tag>
</template>
