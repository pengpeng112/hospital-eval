import { defineStore } from 'pinia'
import { login as apiLogin, logout as apiLogout, me as apiMe } from '@/api/auth'

const TOKEN_KEY = 'eval_token'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null,
    roles: [],
    mustChangePwd: false
  }),
  actions: {
    async login(form) {
      const data = await apiLogin(form)
      this.token = data.token
      this.user = data.user
      this.roles = (data.user && data.user.roles) || []
      this.mustChangePwd = !!data.mustChangePwd
      localStorage.setItem(TOKEN_KEY, data.token)
      return data
    },
    async fetchMe() {
      const user = await apiMe()
      this.user = user
      this.roles = (user && user.roles) || []
      return user
    },
    async logout() {
      try {
        await apiLogout()
      } catch (e) {
        // 后端登出失败不阻断本地清理
      }
      this.token = ''
      this.user = null
      this.roles = []
      this.mustChangePwd = false
      localStorage.removeItem(TOKEN_KEY)
    },
    /** 当前用户是否拥有 codes 中任一角色。 */
    hasRole(...codes) {
      return codes.some((c) => this.roles.includes(c))
    },
    /** 改密成功后解除强制改密锁。 */
    clearMustChangePwd() {
      this.mustChangePwd = false
    }
  }
})
