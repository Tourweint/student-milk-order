/**
 * 配送记录接口
 */
const { get } = require('../utils/request')

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

module.exports = {
  getRecordList,
  getPendingQuantity,
  getParentHome
}
