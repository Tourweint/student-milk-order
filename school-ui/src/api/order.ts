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

// ==================== 过敏/禁忌软警示（只提示，不拦截） ====================

/** 受控过敏原选项（学生禁忌与奶品过敏原共用一套编码；含两侧文案） */
export function getAllergyOptions() {
  return get('/order/allergy-options')
}

/**
 * 下单前预检：命中清单（空数组 = 无警示）。
 *
 * productIds 用逗号拼接：axios 默认把数组序列化成 `productIds[]=1&productIds[]=2`，
 * Spring 的 `@RequestParam List<Long>` 只认重复参数或逗号分隔值，因此这里显式拼串。
 */
export function checkAllergy(params: { studentId: number; productIds: number[] }) {
  return get('/order/allergy-check', {
    studentId: params.studentId,
    productIds: params.productIds.join(',')
  })
}
