/**
 * 订单接口
 */
const { get, post, put } = require('../utils/request')

/** 订单分页列表：{ pageNum, pageSize, status, startDate, endDate }（家长自动限定自己孩子） */
function getOrderList(params) {
  return get('/order/list', params)
}

/** 订单详情（含明细） */
function getOrderDetail(id) {
  return get('/order/' + id)
}

/**
 * 支付结果查单（模拟微信查单）：待支付订单会主动向微信侧查单，
 * 已扣款但回调丢失时在同一路径补偿落账；返回订单当前状态码
 */
function getPayResult(id) {
  return get('/order/' + id + '/pay-result')
}

/** 创建订单：{ studentId, packageId, deliveryStartDate, deliveryEndDate, items:[{productId,quantity}], remark } */
function createOrder(data) {
  return post('/order', data)
}

/** 模拟支付已升级为微信支付模拟链路，见 api/pay.js 与 utils/pay.js */

/** 退订（reason 为 query 参数） */
function cancelOrder(id, reason) {
  return put('/order/cancel/' + id, null, { reason })
}

/**
 * 过敏/禁忌预检（只提示不拦截）：返回命中清单，空数组表示无警示。
 * productIds 逗号拼接——后端是 `@RequestParam List<Long>`，只认重复参数或逗号分隔值。
 */
function checkAllergy(studentId, productIds) {
  return get('/order/allergy-check', {
    studentId,
    productIds: (productIds || []).join(',')
  })
}

/** 受控过敏原选项（学生侧禁忌与奶品侧过敏原共用一套编码） */
function getAllergyOptions() {
  return get('/order/allergy-options')
}

module.exports = {
  getOrderList,
  getOrderDetail,
  getPayResult,
  createOrder,
  cancelOrder,
  checkAllergy,
  getAllergyOptions
}
