<script setup>
import { onMounted, ref } from 'vue'
import { listDepts } from '@/api/system'

/**
 * 科室下拉（TASK-13 步骤 1）：listDepts，可按 deptType 过滤，v-model 绑定科室 id。
 */
const props = defineProps({
  modelValue: { type: Number, default: null },
  deptType: { type: String, default: '' },
  placeholder: { type: String, default: '选择科室' },
  clearable: { type: Boolean, default: true },
  filterable: { type: Boolean, default: true }
})

const emit = defineEmits(['update:modelValue'])

const options = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const params = {}
    if (props.deptType) params.deptType = props.deptType
    options.value = (await listDepts(params)) || []
  } finally {
    loading.value = false
  }
}

function onChange(val) {
  emit('update:modelValue', val == null ? null : val)
}

onMounted(load)
</script>

<template>
  <el-select
    :model-value="modelValue"
    :clearable="clearable"
    :filterable="filterable"
    :loading="loading"
    :placeholder="placeholder"
    @change="onChange"
  >
    <el-option v-for="d in options" :key="d.id" :label="d.deptName" :value="d.id" />
  </el-select>
</template>
