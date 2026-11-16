<script setup>
defineProps({
  task: { type: String, required: true }
})
</script>

<template>
  <div class="eval-card placeholder">
    <span>本页面将在 {{ task }} 实现</span>
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  color: #909399;
}
</style>
