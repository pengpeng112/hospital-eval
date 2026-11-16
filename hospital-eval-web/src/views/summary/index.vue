<script setup>
import { useRoute } from 'vue-router'
import Placeholder from '@/components/Placeholder.vue'

// 当期 / 历史共用本页（TASK-15 实现），通过路由 meta.mode 区分
const route = useRoute()
const mode = route.meta && route.meta.mode
</script>

<template>
  <Placeholder :task="mode === 'history' ? 'TASK-15（历史版本）' : 'TASK-15（当期版本）'" />
</template>
