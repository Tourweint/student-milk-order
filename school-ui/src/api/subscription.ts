import { get, post, put, del } from '@/utils/request'

/** 续订计划列表 */
export function getSubscriptionList(params: { pageNum: number; pageSize: number; status?: number }) {
  return get('/subscription/list', params)
}

/** 续订计划详情 */
export function getSubscriptionById(id: number) {
  return get(`/subscription/${id}`)
}

/** 开启自动续订 */
export function createSubscription(data: any) {
  return post('/subscription', data)
}

/** 修改续订计划 */
export function updateSubscription(data: any) {
  return put('/subscription', data)
}

/** 暂停续订（已开启→已暂停，暂停期间不续订）；keepPendingTasks=false 同时取消未配送任务 */
export function pauseSubscription(id: number, reason?: string, keepPendingTasks = true) {
  return put(`/subscription/pause/${id}`, { reason, keepPendingTasks })
}

/** 恢复续订（已暂停→已开启，续订时间顺延） */
export function resumeSubscription(id: number) {
  return put(`/subscription/resume/${id}`)
}

/** 关闭自动续订（终止订阅）：terminateNow=false 送完当前周期；true 立即取消未配送任务 */
export function deleteSubscription(id: number, terminateNow = false, reason?: string) {
  return del(`/subscription/${id}`, { params: { terminateNow, reason } })
}

/** 手动触发续订（测试用） */
export function triggerSubscription(id: number) {
  return post(`/subscription/trigger/${id}`)
}
