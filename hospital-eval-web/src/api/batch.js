import request from '@/utils/request'

/** 发起跑批：POST /api/batch/run */
export function runBatch(data) {
  return request.post('/api/batch/run', data)
}

/** 批次详情与子任务统计：GET /api/batch/{batchId} */
export function getBatch(batchId) {
  return request.get(`/api/batch/${batchId}`)
}

/** 批次子任务分页：GET /api/batch/{batchId}/tasks */
export function listBatchTasks(batchId, params) {
  return request.get(`/api/batch/${batchId}/tasks`, { params })
}

/** 批次分页列表：GET /api/batch */
export function listBatches(params) {
  return request.get('/api/batch', { params })
}
