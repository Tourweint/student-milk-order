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

/** 关闭续订计划 */
function closePlan(id) {
  return del('/subscription/' + id)
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
  closePlan,
  triggerRenewal
}
