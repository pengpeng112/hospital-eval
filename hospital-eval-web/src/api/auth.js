import request from '@/utils/request'

/** 登录：POST /api/auth/login → {token, mustChangePwd, user} */
export function login(data) {
  return request.post('/api/auth/login', data)
}

/** 登出：POST /api/auth/logout */
export function logout() {
  return request.post('/api/auth/logout')
}

/** 当前用户：GET /api/auth/me */
export function me() {
  return request.get('/api/auth/me')
}

/** 修改密码：POST /api/auth/change-password {oldPassword, newPassword} */
export function changePassword(data) {
  return request.post('/api/auth/change-password', data)
}
