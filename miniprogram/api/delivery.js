/**
 * 配送记录接口
 */
const { get, request } = require('../utils/request')

/** 配送记录分页：{ pageNum, pageSize, deliveryDate, signStatus }（家长自动限定自己孩子） */
function getRecordList(params) {
  return get('/delivery/record/list', params)
}

/** 剩余待配送盒数：孩子名下未完成（待配送+配送中）任务的盒数合计（家长自动限定自己孩子） */
function getPendingQuantity() {
  return get('/delivery/task/pending-quantity')
}

/** 家长端首页聚合：剩余待配送 + 下次配送日 + 近期拒收（家长自动限定自己孩子） */
function getParentHome() {
  return get('/delivery/task/parent-home')
}

/** 家长端「当日豁免」概览：今天可豁免的待配送任务数 + 本月剩余次数 */
function getParentExemption() {
  return get('/delivery/task/parent-exemption')
}

/**
 * 家长端「当日豁免」：取消今天尚未送出（待配送）的全部任务。
 * reason 走 query（与「取消任务」同一风格）；次数按学生×自然月计，超限后端返回业务错误。
 */
function exemptToday(reason) {
  return request({
    url: '/delivery/task/parent-exempt-today',
    method: 'POST',
    query: reason ? { reason } : undefined
  })
}

module.exports = {
  getRecordList,
  getPendingQuantity,
  getParentHome,
  getParentExemption,
  exemptToday
}
