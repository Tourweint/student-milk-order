import { get } from '@/utils/request'

/** 角色列表 */
export function getRoleList() {
  return get('/system/role/list')
}

/** 操作日志分页 */
export function getOperationLogList(params: {
  pageNum: number
  pageSize: number
  username?: string
  status?: number
  startTime?: string
  endTime?: string
}) {
  return get('/system/log/list', params)
}
