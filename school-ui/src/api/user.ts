import { get, post, put, del } from '@/utils/request'

/** 分页查询用户列表 */
export function getUserList(params: { pageNum: number; pageSize: number; keyword?: string }) {
  return get('/user/list', params)
}

/** 获取用户详情 */
export function getUserById(id: number) {
  return get(`/user/${id}`)
}

/** 角色列表（下拉选择用） */
export function getRoleList() {
  return get('/user/roles')
}

/** 新增用户 */
export function saveUser(data: any) {
  return post('/user', data)
}

/** 修改用户 */
export function updateUser(data: any) {
  return put('/user', data)
}

/** 删除用户 */
export function deleteUser(id: number) {
  return del(`/user/${id}`)
}

/** 重置密码 */
export function resetPassword(id: number, newPassword: string) {
  return put(`/user/${id}/password`, { newPassword })
}
