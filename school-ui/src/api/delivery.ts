import { get, post, put } from '@/utils/request'

/** 生成配送任务（按日期，可选班级） */
export function generateDeliveryTasks(deliveryDate: string, classId?: number) {
  return post('/delivery/task/generate', null, { params: { deliveryDate, classId } })
}

/** 配送任务分页 */
export function getDeliveryTaskList(params: {
  pageNum: number
  pageSize: number
  deliveryDate?: string
  classId?: number
  status?: number
}) {
  return get('/delivery/task/list', params)
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

/** 拒收配送记录 */
export function rejectDeliveryRecord(recordId: number, reason?: string) {
  return post('/delivery/record/reject', null, { params: { recordId, reason } })
}
