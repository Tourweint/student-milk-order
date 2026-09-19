import { get, post, put } from '@/utils/request'

/** 订单分页列表 */
export function getOrderList(params: {
  pageNum: number
  pageSize: number
  classId?: number
  studentId?: number
  status?: number
  startDate?: string
  endDate?: string
}) {
  return get('/order/list', params)
}

/** 订单详情（含明细） */
export function getOrderById(id: number) {
  return get(`/order/${id}`)
}

/** 创建订单 */
export function createOrder(data: any) {
  return post('/order', data)
}

/** 模拟支付 */
export function payOrder(id: number) {
  return post(`/order/pay/${id}`)
}

/** 退订订单 */
export function cancelOrder(id: number, reason?: string) {
  return put(`/order/cancel/${id}`, null, { params: { reason } })
}

/** 完成订单（配送中→已完成） */
export function completeOrder(id: number) {
  return put(`/order/complete/${id}`)
}
