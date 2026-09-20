import { get, post, put } from '@/utils/request'

/** 生成配送任务（按日期，可选班级） */
export function generateDeliveryTasks(deliveryDate: string, classId?: number) {
  return post('/delivery/task/generate', null, { params: { deliveryDate, classId } })
}

/** 配送任务分页（deliveryDate+dateEnd 组合为日期区间；orderNo 按订单号模糊筛选） */
export function getDeliveryTaskList(params: {
  pageNum: number
  pageSize: number
  deliveryDate?: string
  dateEnd?: string
  orderNo?: string
  classId?: number
  status?: number
}) {
  return get('/delivery/task/list', params)
}

/** 今日已送出：按日期（可选班级）批量开始配送，联动订单进入配送中（此后不可退订） */
export function batchStartDeliveryTasks(data: { deliveryDate: string; classId?: number }) {
  return put('/delivery/task/batch-start', data)
}

/** 某配送日期按班级汇总任务状态（配送站面板今日概览） */
export function getDailyTaskSummary(deliveryDate: string) {
  return get('/delivery/task/summary', { deliveryDate })
}

/**
 * 配送日平移（仅管理员）：把待配送任务平移到目标日。
 * 目标日已有同订单同品种任务则合并数量，否则新建；源任务配送中 / 目标日任务已开始配送时拒绝整批。
 */
export function shiftDeliveryTasks(data: { taskIds: number[]; targetDate: string }) {
  return post('/delivery/task/shift', data)
}

/**
 * 学期末摊平（仅管理员）：把订单从今天起的剩余盒数重排到截止日当天及之前的待配送任务上
 * （每天 ≤2 盒）；截止日之后的待配送任务作废。可重复执行。
 */
export function rebalanceDeliveryQuantities(data: { orderId: number; deadline: string }) {
  return post('/delivery/task/rebalance', data)
}

/** 配送前缺货批量取消：取消某日期某奶品的全部待配送任务（仅取消该期，配额按台账回补） */
export function stockoutCancelTasks(data: { deliveryDate: string; productId: number; reason?: string }) {
  return put('/delivery/task/stockout-cancel', data)
}

/** 开始配送 */
export function startDeliveryTask(id: number) {
  return put(`/delivery/task/start/${id}`)
}

/** 取消配送任务 */
export function cancelDeliveryTask(id: number, reason?: string) {
  return put(`/delivery/task/cancel/${id}`, null, { params: { reason } })
}

/** 配送记录分页 */
export function getDeliveryRecordList(params: {
  pageNum: number
  pageSize: number
  deliveryDate?: string
  classId?: number
  studentId?: number
  signStatus?: number
}) {
  return get('/delivery/record/list', params)
}

/** 签收配送记录 */
export function signDeliveryRecord(data: { recordId: number; signPerson?: string; remark?: string }) {
  return post('/delivery/record/sign', data)
}

/** 批量签收（按配送日期，可选班级；班主任由后端强制限定本班） */
export function batchSignDeliveryRecords(data: { deliveryDate: string; classId?: number }) {
  return post('/delivery/record/batch-sign', data)
}

/**
 * 拒收配送记录（结构化原因，并在同事务内自动落补送）。
 * reasonCode：DAMAGED / SOUR / WRONG_PRODUCT / SHORTAGE / OTHER；reason 为兼容旧调用方的自由文本。
 */
export function rejectDeliveryRecord(params: {
  recordId: number
  reasonCode?: string
  reasonDetail?: string
  reason?: string
}) {
  return post('/delivery/record/reject', null, { params })
}

/** 待签收汇总：某配送日期（默认今天）「已送出未签收」记录按班级聚合（班主任限本班） */
export function getPendingSign(deliveryDate?: string) {
  return get('/delivery/record/pending-sign', { deliveryDate })
}
