<script setup>
import { computed } from 'vue'

/**
 * 复核状态标签（TASK-15 步骤 1，文案按任务书）：
 * DRAFT 灰"草稿" / L1_SUBMIT 蓝"已提交初审" / L2_PASS 绿"内审通过" /
 * L2_REJECT 红"退回整改" / L3_ARCHIVED 深绿"已终审归档"。
 */
const props = defineProps({
  status: { type: String, default: '' }
})

const MAP = {
  DRAFT: { label: '草稿', tag: 'info', dark: false },
  L1_SUBMIT: { label: '已提交初审', tag: 'primary', dark: false },
  L2_PASS: { label: '内审通过', tag: 'success', dark: false },
  L2_REJECT: { label: '退回整改', tag: 'danger', dark: false },
  L3_ARCHIVED: { label: '已终审归档', tag: 'success', dark: true }
}

const item = computed(() => MAP[props.status] || { label: props.status || '-', tag: 'info', dark: false })
</script>

<template>
  <el-tag
    :type="item.tag"
    :effect="item.dark ? 'dark' : 'light'"
    size="small"
    disable-transitions
  >{{ item.label }}</el-tag>
</template>
