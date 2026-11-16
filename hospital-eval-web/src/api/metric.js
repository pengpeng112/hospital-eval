import request, { download } from '@/utils/request'

/** 章节树：GET /api/metric/tree */
export function getMetricTree() {
  return request.get('/api/metric/tree')
}

/** 指标分页列表：GET /api/metric/list */
export function listMetrics(params) {
  return request.get('/api/metric/list', { params })
}

/** 指标详情：GET /api/metric/{id} */
export function getMetric(id) {
  return request.get(`/api/metric/${id}`)
}

/** 修改指标（changeReason 必填）：PUT /api/metric/{id} */
export function updateMetric(id, data) {
  return request.put(`/api/metric/${id}`, data)
}

/** 科室实例分页：GET /api/scope/list */
export function listScopes(params) {
  return request.get('/api/scope/list', { params })
}

/** 新建科室实例：POST /api/scope */
export function createScope(data) {
  return request.post('/api/scope', data)
}

/** 修改科室实例：PUT /api/scope/{id} */
export function updateScope(id, data) {
  return request.put(`/api/scope/${id}`, data)
}

/** 职能部门维度汇总：GET /api/scope/func-dept-summary */
export function funcDeptSummary() {
  return request.get('/api/scope/func-dept-summary')
}

/** 科室实例导出 xlsx：GET /api/scope/export */
export function exportScopes(params) {
  return download('/api/scope/export', params, '科室实例.xlsx')
}

/** 科室实例导入：POST /api/scope/import（multipart file） */
export function importScopes(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/api/scope/import', form)
}

/** 同步 HIS 科室编码：POST /api/scope/sync-dept-codes（仅 ADMIN） */
export function syncDeptCodes() {
  return request.post('/api/scope/sync-dept-codes')
}
