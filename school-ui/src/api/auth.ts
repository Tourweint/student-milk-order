import { post, get } from '@/utils/request'

/** 登录 */
export function login(data: { username: string; password: string }) {
  return post('/auth/login', data)
}

/** 注册 */
export function register(data: any) {
  return post('/auth/register', data)
}

/** 获取当前用户信息 */
export function getCurrentUser() {
  return get('/auth/me')
}
