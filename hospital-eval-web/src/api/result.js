import request, { download } from '@/utils/request'

/** 结果汇总分页（全院行）：GET /api/result/summary */
export function listSummary(params) {
  return request.get('/api/result/summary', { params })
}

/** 指标科室行结果：GET /api/result/metric/{metricId}/scopes */
export function listMetricScopes(metricId, params) {
  return request.get(`/api/result/metric/${metricId}/scopes`, { params })
}

/** 指标详情面板：GET /api/result/metric/{metricId}/panel */
export function getMetricPanel(metricId) {
  return request.get(`/api/result/metric/${metricId}/panel`)
}

/** 结果批次列表：GET /api/result/batches */
export function listResultBatches() {
  return request.get('/api/result/batches')
}

/** 跑批/混合明细分页：GET /api/result/detail */
export function listDetail(params) {
  return request.get('/api/result/detail', { params })
}

/** 明细导出 xlsx：GET /api/result/detail/export */
export function exportDetail(params) {
  return download('/api/result/detail/export', params, '明细.xlsx')
}

/** 电子病历 360 跳转地址：GET /api/emr/360-url */
export function get360Url(params) {
  return request.get('/api/emr/360-url', { params })
}

/** 病历文书列表：GET /api/emr/documents */
export function listEmrDocuments(params) {
  return request.get('/api/emr/documents', { params })
}

/** 病历文书正文（纯文本 ≤20000 字）：GET /api/emr/document-content */
export function getEmrDocumentContent(params) {
  return request.get('/api/emr/document-content', { params })
}

/** 总表回填导出 xlsx：GET /api/export/master */
export function exportMaster(params) {
  return download('/api/export/master', params, '等级评审总表.xlsx')
}
