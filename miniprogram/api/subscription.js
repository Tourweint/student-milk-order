/**
 * 自动续订接口
 */
const { get, post, put, del } = require('../utils/request')

/** 续订计划分页：{ pageNum, pageSize, status }（家长自动限定自己孩子） */
function getPlanList(params) {
  return get('/subscription/list', params)
}

/** 续订计划详情 */
function getPlanDetail(id) {
  return get('/subscription/' + id)
}

/** 开启续订：{ studentId, packageId, originalOrderId, cycleType, remark } */
function createPlan(data) {
  return post('/subscription', data)
}

/** 修改续订计划 */
function updatePlan(data) {
  return put('/subscription', data)
}

/** 暂停续订（已开启→已暂停）；keepPendingTasks=false 同时取消未配送任务 */
function pausePlan(id, reason, keepPendingTasks) {
  return put('/subscription/pause/' + id, {
    reason: reason || '',
    keepPendingTasks: keepPendingTasks !== false
  })
}

/** 恢复续订（已暂停→已开启，续订时间顺延） */
function resumePlan(id) {
  return put('/subscription/resume/' + id)
}

/** 关闭续订计划（终止订阅）：terminateNow=true 立即取消未配送任务 */
function closePlan(id, terminateNow) {
  return del('/subscription/' + id + '?terminateNow=' + (terminateNow ? 'true' : 'false'))
}

/** 手动触发续订，返回新订单 ID */
function triggerRenewal(id) {
  return post('/subscription/trigger/' + id)
}

module.exports = {
  getPlanList,
  getPlanDetail,
  createPlan,
  updatePlan,
  pausePlan,
  resumePlan,
  closePlan,
  triggerRenewal
}
