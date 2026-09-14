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

/** 配送前缺货批量取消：取消某日期某奶品的全部待配送任务（配额按日回补，不影响主订阅） */
export function stockoutCancelTasks(data: { deliveryDate: string; productId: number; reason?: string }) {
  return put('/delivery/task/stockout-cancel', data)
}

/** 配送任务详情 */
export function getDeliveryTaskById(id: number) {
  return get(`/delivery/task/${id}`)
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

/** 拒收配送记录 */
export function rejectDeliveryRecord(recordId: number, reason?: string) {
  return post('/delivery/record/reject', null, { params: { recordId, reason } })
}
