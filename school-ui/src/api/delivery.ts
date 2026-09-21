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

/**
 * 配送日历重排（仅管理员）：把日期范围内落在周末（非补课日）/停送日的待配送任务，
 * 按规则并入前面的工作日（周六、周日各提前 2 天；停送日并入前一个有效配送日）。
 * 需先开启系统参数 delivery.weekend.stop。
 */
export function calendarRebalanceTasks(data: { startDate: string; endDate: string }) {
  return post('/delivery/task/calendar-rebalance', data)
}

// ==================== 配送例外（停送日/补课日，仅管理员） ====================

/** 配送例外列表（可按日期范围） */
export function getDeliveryExceptionList(params?: { startDate?: string; endDate?: string }) {
  return get('/delivery/exception/list', params)
}

/** 新增/修改配送例外（日期唯一；type：1-停送，2-补送/补课） */
export function saveDeliveryException(data: {
  id?: number
  exceptionDate: string
  type: number
  remark?: string
}) {
  return post('/delivery/exception/save', data)
}

/** 删除配送例外 */
export function deleteDeliveryException(id: number) {
  return post('/delivery/exception/delete', { id })
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

// ==================== 奶站「当日未送达申报」 ====================

/**
 * 申报某任务当日未送达（ADMIN / DELIVERY）。
 * 该任务已被标记「已送出」但物理上没送到：申报后它被排除出自动签收兜底候选集，
 * 并进入待跟进列表；申报本身不改任何任务/订单状态。
 */
export function reportUndelivered(data: { taskId: number; reason: string }) {
  return post('/delivery/task/undelivered-report', data)
}

/** 未送达申报待跟进列表（班主任限本班） */
export function getUndeliveredReportList(params: {
  pageNum: number
  pageSize: number
  deliveryDate?: string
  handleStatus?: number
  classId?: number
}) {
  return get('/delivery/task/undelivered-report/list', params)
}

/** 标记已跟进（仅 ADMIN）：只写跟进说明，实际处置仍走签收/拒收/取消出口 */
export function handleUndeliveredReport(id: number, remark?: string) {
  return put(`/delivery/task/undelivered-report/${id}/handle`, null, { params: { remark } })
}
