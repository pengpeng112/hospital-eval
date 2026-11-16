import request from '@/utils/request'

/** 人工上报任务分页：GET /api/report/tasks */
export function listReportTasks(params) {
  return request.get('/api/report/tasks', { params })
}

/** 人工填报数值：POST /api/report/manual-value */
export function saveManualValue(data) {
  return request.post('/api/report/manual-value', data)
}

/** 混合池分页（role=DEN）：GET /api/report/hybrid-pool */
export function listHybridPool(params) {
  return request.get('/api/report/hybrid-pool', { params })
}

/** 混合池勾选：POST /api/report/hybrid-select */
export function hybridSelect(data) {
  return request.post('/api/report/hybrid-select', data)
}

/** 上传佐证附件：POST /api/evidence/upload（multipart） */
export function uploadEvidence(file, meta) {
  const form = new FormData()
  form.append('file', file)
  Object.keys(meta || {}).forEach((k) => {
    if (meta[k] !== undefined && meta[k] !== null) {
      form.append(k, meta[k])
    }
  })
  return request.post('/api/evidence/upload', form)
}

/** 佐证附件列表：GET /api/evidence/list */
export function listEvidence(params) {
  return request.get('/api/evidence/list', { params })
}

/** 佐证预览地址：GET /api/evidence/{id}/preview-url */
export function getEvidencePreviewUrl(id) {
  return request.get(`/api/evidence/${id}/preview-url`)
}

/** 删除佐证附件：DELETE /api/evidence/{id} */
export function deleteEvidence(id) {
  return request.delete(`/api/evidence/${id}`)
}

/** 三级复核-提交：POST /api/audit/submit */
export function auditSubmit(data) {
  return request.post('/api/audit/submit', data)
}

/** 三级复核-通过：POST /api/audit/approve */
export function auditApprove(data) {
  return request.post('/api/audit/approve', data)
}

/** 三级复核-退回（opinion ≥5 字）：POST /api/audit/reject */
export function auditReject(data) {
  return request.post('/api/audit/reject', data)
}

/** 三级复核-归档（按指标+年份）：POST /api/audit/archive */
export function auditArchive(data) {
  return request.post('/api/audit/archive', data)
}

/** 三级复核-解锁：POST /api/audit/unlock */
export function auditUnlock(data) {
  return request.post('/api/audit/unlock', data)
}

/** 复核留痕查询：GET /api/audit/logs */
export function listAuditLogs(params) {
  return request.get('/api/audit/logs', { params })
}

/** 工作台-总览：GET /api/workbench/overview */
export function workbenchOverview(params) {
  return request.get('/api/workbench/overview', { params })
}

/** 工作台-待办：GET /api/workbench/todos */
export function workbenchTodos(params) {
  return request.get('/api/workbench/todos', { params })
}

/** 工作台-科室进度：GET /api/workbench/dept-progress */
export function workbenchDeptProgress(params) {
  return request.get('/api/workbench/dept-progress', { params })
}

/** 工作台-预警：GET /api/workbench/alerts */
export function workbenchAlerts(params) {
  return request.get('/api/workbench/alerts', { params })
}
