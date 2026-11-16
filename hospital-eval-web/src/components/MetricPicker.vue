<script setup>
import { ref } from 'vue'
import { listMetrics } from '@/api/metric'

/**
 * 指标远程搜索下拉（TASK-14 步骤 3）：listMetrics({keyword, size:20})，
 * 选项显示"编号 代号 名称"，v-model 绑定指标 id，并 emit 选中的完整指标对象。
 */
const props = defineProps({
  modelValue: { type: Number, default: null },
  placeholder: { type: String, default: '输入指标编号 / 代号 / 名称搜索' }
})

const emit = defineEmits(['update:modelValue', 'select'])

const options = ref([])
const loading = ref(false)

async function remoteSearch(keyword) {
  loading.value = true
  try {
    const params = { page: 1, size: 20 }
    if (keyword) params.keyword = keyword
    const data = await listMetrics(params)
    options.value = data.list || []
  } finally {
    loading.value = false
  }
}

function labelOf(m) {
  return [m.metricCode, m.aliasCode, m.metricName].filter(Boolean).join(' ')
}

function onChange(val) {
  if (val == null) {
    emit('update:modelValue', null)
    emit('select', null)
    return
  }
  const row = options.value.find((m) => m.id === val)
  emit('update:modelValue', val)
  emit('select', row || null)
}

remoteSearch('')
</script>

<template>
  <el-select
    :model-value="modelValue"
    filterable
    remote
    clearable
    :remote-method="remoteSearch"
    :loading="loading"
    :placeholder="placeholder"
    style="width: 420px"
    @change="onChange"
  >
    <el-option v-for="m in options" :key="m.id" :label="labelOf(m)" :value="m.id" />
  </el-select>
</template>
