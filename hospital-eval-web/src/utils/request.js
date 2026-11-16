import axios from 'axios'
import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'eval_token'

const request = axios.create({
  baseURL: '',
  timeout: 120000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    if (response.config.responseType === 'blob') {
      return response
    }
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      if (body.code === 401) {
        localStorage.removeItem(TOKEN_KEY)
        if (window.location.pathname !== '/login') {
          window.location.href = '/login'
        }
        return Promise.reject(new Error(body.msg || '未登录'))
      }
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
      return Promise.reject(error)
    }
    const msg = (error.response && error.response.data && error.response.data.msg)
      || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

/**
 * blob 方式下载文件。文件名优先取响应头 Content-Disposition（decodeURIComponent）。
 */
export async function download(url, params, defaultName = 'download.xlsx') {
  const response = await request.get(url, { params, responseType: 'blob' })
  const header = response.headers && response.headers['content-disposition']
  let filename = defaultName
  if (header) {
    const decoded = decodeURIComponent(header)
    const star = decoded.match(/filename\*=UTF-8''([^;]+)/i)
    const plain = decoded.match(/filename="?([^";]+)"?/i)
    filename = (star && star[1]) || (plain && plain[1]) || defaultName
  }
  const blobUrl = URL.createObjectURL(response.data)
  const a = document.createElement('a')
  a.href = blobUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(blobUrl)
}

export default request
