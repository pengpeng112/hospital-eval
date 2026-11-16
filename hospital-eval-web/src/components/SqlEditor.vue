<script setup>
import { computed } from 'vue'
import { Codemirror } from 'vue-codemirror'
import { sql, PLSQL } from '@codemirror/lang-sql'

/**
 * SQL 编辑器（TASK-14 步骤 1）：vue-codemirror + @codemirror/lang-sql（PLSQL 方言）。
 * basicSetup 默认开启行号与括号匹配；中文按 UTF-8 正常显示。
 * 前端不改写 SQL：编辑内容原样经 modelValue 传给父组件。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  readonly: { type: Boolean, default: false },
  height: { type: String, default: '420px' }
})

const emit = defineEmits(['update:modelValue'])

const extensions = computed(() => [sql({ dialect: PLSQL })])

function onUpdate(value) {
  emit('update:modelValue', value == null ? '' : value)
}
</script>

<template>
  <div class="sql-editor" :style="{ height }">
    <Codemirror
      :model-value="modelValue"
      :extensions="extensions"
      :readonly="readonly"
      :disabled="readonly"
      :style="{ height: '100%' }"
      placeholder="-- 输入 SQL（仅 SELECT / WITH）"
      @update:model-value="onUpdate"
    />
  </div>
</template>

<style scoped>
.sql-editor {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
}
.sql-editor :deep(.cm-editor) {
  font-size: 13px;
}
.sql-editor :deep(.cm-gutters) {
  background: #fafbfc;
  border-right: 1px solid #e6e8eb;
}
</style>
