import { get, post, put } from '@/utils/request'

/** 退款单状态：1-待审核，2-已审核待退款，3-已退款，4-已拒绝，5-已取消 */
export type RefundStatus = 1 | 2 | 3 | 4 | 5

/** 退款单（退款域父过程） */
export interface RefundOrderVO {
  id: number
  refundNo: string
  orderId: number
  orderNo: string
  studentId: number
  studentName?: string
  userId?: number
  /** 申请盒数（参考值） */
  applyBoxCount?: number
  /** 该订单截至本单的累计已退盒数 */
  refundedBoxes?: number
  refundAmount?: number
  status: RefundStatus
  statusText: string
  applyReason?: string
  auditRemark?: string
  auditTime?: string
  refundTime?: string
  createTime?: string
}

/** 可退期次（R1 口径：待配送 ∪ 缺货取消；补送免费盒已扣除） */
export interface RefundableTaskVO {
  taskId: number
  taskNo: string
  productId: number
  productName?: string
  deliveryDate: string
  quantity: number
  refundableBoxes: number
  /** true=待配送（执行时作废）；false=缺货取消（本就终态） */
  pending: boolean
  statusText?: string
}

/** 退款预览（只读探测，与执行共用同一金额口径） */
export interface RefundPreviewVO {
  orderId: number
  orderNo: string
  orderStatus: number
  orderStatusText: string
  contractTotalBoxes?: number
  refundableBoxes: number
  refundableTasks: RefundableTaskVO[]
  refundedBoxes: number
  refundedAmount: number
  estimatedAmount: number
  hasActiveRefund: boolean
}

/** 毕业清算逐单处理结果 */
export interface SettlementItemVO {
  orderId: number
  orderNo: string
  orderStatus: number
  action: 'CANCELLED_UNPAID' | 'REFUNDED' | 'SKIPPED' | 'FAILED'
  refundNo?: string
  refundedBoxes?: number
  refundAmount?: number
  message?: string
}

/** 毕业清算结果 */
export interface SettlementResultVO {
  studentId: number
  totalOrders: number
  cancelledCount: number
  refundedCount: number
  skippedCount: number
  failedCount: number
  totalRefundAmount: number
  items: SettlementItemVO[]
}

/** 退款单分页（管理员：全部退款单） */
export function getRefundList(params: {
  pageNum: number
  pageSize: number
  status?: number
  orderNo?: string
  studentId?: number
}) {
  return get('/refund/list', params)
}

/** 本人（家长绑定学生）退款单分页 */
export function getMyRefunds(params: { pageNum: number; pageSize: number; status?: number }) {
  return get('/refund/order/my', params)
}

/** 申请退款（同一订单已有进行中退款单时会被拒绝） */
export function applyRefund(orderId: number, data: { applyBoxCount: number; reason?: string }) {
  return post(`/refund/order/${orderId}`, data)
}

/** 退款预览（只读，不落库） */
export function getRefundPreview(orderId: number) {
  return get(`/refund/order/${orderId}/preview`)
}

/** 审核退款单：通过 → 已审核待退款；拒绝 → 已拒绝（可重新申请） */
export function auditRefund(id: number, data: { approved: boolean; remark?: string }) {
  return put(`/refund/${id}/audit`, data)
}

/** 执行退款（作废可退期次 + 计价 + 回补配额 + 退款单 2→3） */
export function executeRefund(id: number) {
  return put(`/refund/${id}/execute`)
}

/** 毕业清算（管理员，幂等） */
export function settleStudent(studentId: number) {
  return post(`/student/${studentId}/settle`)
}
