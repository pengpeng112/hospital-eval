import request from '@/utils/request'

/** 科室列表：GET /api/system/depts */
export function listDepts(params) {
  return request.get('/api/system/depts', { params })
}

/** 新建科室：POST /api/system/depts（仅 ADMIN） */
export function createDept(data) {
  return request.post('/api/system/depts', data)
}

/** 用户分页列表：GET /api/system/users */
export function listUsers(params) {
  return request.get('/api/system/users', { params })
}

/** 新建用户：POST /api/system/users */
export function createUser(data) {
  return request.post('/api/system/users', data)
}

/** 编辑用户：PUT /api/system/users/{id} */
export function updateUser(id, data) {
  return request.put(`/api/system/users/${id}`, data)
}

/** 重置密码：POST /api/system/users/{id}/reset-password */
export function resetPassword(id) {
  return request.post(`/api/system/users/${id}/reset-password`)
}

/** 操作日志分页：GET /api/system/oper-logs */
export function listOperLogs(params) {
  return request.get('/api/system/oper-logs', { params })
}
