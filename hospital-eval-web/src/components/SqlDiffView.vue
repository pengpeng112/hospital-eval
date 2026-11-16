<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { MergeView } from '@codemirror/merge'
import { EditorView, basicSetup } from 'codemirror'

/**
 * SQL 左右对比视图（TASK-14 步骤 2）：@codemirror/merge MergeView，
 * 左侧旧 SQL、右侧新 SQL，两侧只读，高亮差异；卸载时 destroy()。
 */
const props = defineProps({
  oldText: { type: String, default: '' },
  newText: { type: String, default: '' }
})

const container = ref(null)
let mergeView = null

function readonlyConfig(doc) {
  return {
    doc,
    extensions: [basicSetup, EditorView.editable.of(false), EditorView.lineWrapping]
  }
}

function render() {
  if (!container.value) return
  if (mergeView) {
    mergeView.destroy()
    mergeView = null
  }
  mergeView = new MergeView({
    a: readonlyConfig(props.oldText || ''),
    b: readonlyConfig(props.newText || ''),
    parent: container.value,
    gutter: true
  })
}

onMounted(render)

watch(() => [props.oldText, props.newText], render)

onBeforeUnmount(() => {
  if (mergeView) {
    mergeView.destroy()
    mergeView = null
  }
})
</script>

<template>
  <div ref="container" class="sql-diff"></div>
</template>

<style scoped>
.sql-diff :deep(.cm-mergeView) {
  height: 100%;
}
.sql-diff :deep(.cm-editor) {
  font-size: 13px;
}
.sql-diff :deep(.cm-changedLine) {
  background: #fff3e0;
}
.sql-diff :deep(.cm-deletedChunk) {
  background: #fdecea;
}
</style>
