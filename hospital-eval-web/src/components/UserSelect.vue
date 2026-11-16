<script setup>
import { ref, watch } from 'vue'
import { listUsers } from '@/api/system'

/**
 * 用户远程搜索下拉（TASK-13 步骤 1）：listUsers({keyword, size:20})，
 * 选项显示"姓名（科室）"，v-model 绑定用户 id，可清空。
 * initialLabel：父组件已知该 id 对应姓名（如 scope 行的 liaisonName），
 * 在选项尚未加载时用于回显，避免显示裸 id。
 */
const props = defineProps({
  modelValue: { type: Number, default: null },
  placeholder: { type: String, default: '输入姓名搜索用户' },
  initialLabel: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue'])

const options = ref([])
const loading = ref(false)

function labelOf(u) {
  return u.deptName ? `${u.realName}（${u.deptName}）` : String(u.realName)
}

async function remoteSearch(keyword) {
  loading.value = true
  try {
    const params = { page: 1, size: 20 }
    if (keyword) params.keyword = keyword
    const data = await listUsers(params)
    options.value = (data.list || []).filter((u) => u.status === 1)
  } finally {
    loading.value = false
  }
}

// 已绑定 id 但选项尚未回显时，用父组件传入的姓名先补一个选项
watch(
  () => props.modelValue,
  (val) => {
    if (val != null && !options.value.some((u) => u.id === val) && props.initialLabel) {
      options.value = [{ id: val, realName: props.initialLabel, deptName: '' }, ...options.value]
    }
  },
  { immediate: true }
)

function onChange(val) {
  emit('update:modelValue', val == null ? null : val)
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
    @change="onChange"
  >
    <el-option v-for="u in options" :key="u.id" :label="labelOf(u)" :value="u.id" />
  </el-select>
</template>
