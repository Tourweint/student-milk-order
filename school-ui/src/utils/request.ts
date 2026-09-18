import axios, { type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const service: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('token')
    if (token && config.headers) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
service.interceptors.response.use(
  (response: AxiosResponse) => {
    // 二进制下载（blob/arraybuffer）直接返回数据，不走业务状态码判断
    if (response.config.responseType === 'blob' || response.config.responseType === 'arraybuffer') {
      return response.data
    }
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      // 401 未登录，跳转到登录页
      if (res.code === 401) {
        localStorage.removeItem('token')
        localStorage.removeItem('userInfo')
        router.push('/login')
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    // 后端未就绪时的代理/网络层错误：静默交由 request 层自动重试，不弹错误提示
    if (isRetryableError(error)) {
      return Promise.reject(error)
    }
    if (error.response?.status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      router.push('/login')
    } else {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

/** GET 请求在「后端未就绪」窗口期（代理/网络层错误）的自动重试配置 */
const RETRY_TIMES = 5
const RETRY_DELAY_MS = 2000

/**
 * 是否为可重试的代理/网络层错误：
 * - 无响应（直连后端被拒，如 ECONNREFUSED）
 * - vite 代理返回 500 且响应体不是业务 ApiResponse 格式（后端尚未监听时的代理错误）
 * 业务 500（有 code 字段）不重试，避免掩盖真实错误。
 */
function isRetryableError(error: any): boolean {
  if (!error?.response) return true
  const data = error.response.data
  const isBusiness = data && typeof data === 'object' && 'code' in data
  return error.response.status === 500 && !isBusiness
}

// 封装请求方法
export function request<T = any>(config: AxiosRequestConfig): Promise<T> {
  return doRequest<T>(config, RETRY_TIMES)
}

/** 带自动重试的请求：仅 GET（幂等）在可重试错误下等待后重试；POST/PUT/DELETE 不重试，避免重复提交副作用 */
function doRequest<T>(config: AxiosRequestConfig, retriesLeft: number): Promise<T> {
  return service.request(config)
    .then((res) => res as T)
    .catch((error: any) => {
      const isGet = !config.method || config.method.toUpperCase() === 'GET'
      if (isGet && isRetryableError(error) && retriesLeft > 0) {
        return new Promise<T>((resolve) => setTimeout(resolve, RETRY_DELAY_MS))
          .then(() => doRequest<T>(config, retriesLeft - 1))
      }
      throw error
    })
}

export function get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'GET', params, ...config })
}

export function post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'POST', data, ...config })
}

export function put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'PUT', data, ...config })
}

export function del<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request<T>({ url, method: 'DELETE', ...config })
}

export default service
