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

/** 关闭自动续订 */
export function deleteSubscription(id: number) {
  return del(`/subscription/${id}`)
}

/** 手动触发续订（测试用） */
export function triggerSubscription(id: number) {
  return post(`/subscription/trigger/${id}`)
}
