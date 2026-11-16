import request, { download } from '@/utils/request'

/** 大屏总览：GET /api/dashboard/overview */
export function dashboardOverview(params) {
  return request.get('/api/dashboard/overview', { params })
}

/** 下载省平台对账模板 xlsx：GET /api/reconcile/template */
export function downloadReconcileTemplate() {
  return download('/api/reconcile/template', {}, '省平台对账模板.xlsx')
}

/** 导入省平台数据：POST /api/reconcile/import（multipart file） */
export function importReconcile(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/api/reconcile/import', form)
}

/** 对账导入历史：GET /api/reconcile/imports */
export function listReconcileImports() {
  return request.get('/api/reconcile/imports')
}

/** 对账明细分页：GET /api/reconcile/{importId}/rows */
export function listReconcileRows(importId, params) {
  return request.get(`/api/reconcile/${importId}/rows`, { params })
}

/** 对账结果导出 xlsx：GET /api/reconcile/{importId}/export */
export function exportReconcile(importId) {
  return download(`/api/reconcile/${importId}/export`, {}, '省平台对账结果.xlsx')
}
