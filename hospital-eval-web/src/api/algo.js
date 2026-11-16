import request from '@/utils/request'

/** 数据源列表：GET /api/algo/datasources */
export function listDatasources() {
  return request.get('/api/algo/datasources')
}

/** 数据源连通性：POST /api/algo/datasources/{key}/ping */
export function pingDatasource(key) {
  return request.post(`/api/algo/datasources/${key}/ping`)
}

/** SQL 沙箱校验：POST /api/algo/sql/validate */
export function validateSql(data) {
  return request.post('/api/algo/sql/validate', data)
}

/** SQL 试跑（最多 20 行）：POST /api/algo/sql/trial */
export function trialSql(data) {
  return request.post('/api/algo/sql/trial', data)
}

/** 指标科室实例的 SQL 列表：GET /api/algo/sqls */
export function listSqls(params) {
  return request.get('/api/algo/sqls', { params })
}

/** SQL 详情（含注释版/可执行版）：GET /api/algo/sqls/{id} */
export function getSql(id) {
  return request.get(`/api/algo/sqls/${id}`)
}

/** 新建 SQL（version=1）：POST /api/algo/sqls */
export function createSql(data) {
  return request.post('/api/algo/sqls', data)
}

/** 修改 SQL（先写 his 再 version+1）：PUT /api/algo/sqls/{id} */
export function updateSql(id, data) {
  return request.put(`/api/algo/sqls/${id}`, data)
}

/** 启用/停用（版本号不递增）：POST /api/algo/sqls/{id}/toggle */
export function toggleSql(id, data) {
  return request.post(`/api/algo/sqls/${id}/toggle`, data)
}

/** SQL 历史版本列表：GET /api/algo/sqls/{id}/history */
export function listSqlHistory(id) {
  return request.get(`/api/algo/sqls/${id}/history`)
}

/** 历史版本详情：GET /api/algo/sql-his/{hisId} */
export function getSqlHis(hisId) {
  return request.get(`/api/algo/sql-his/${hisId}`)
}
