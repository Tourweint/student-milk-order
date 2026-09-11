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

/** 创建订单：{ studentId, packageId, deliveryStartDate, deliveryEndDate, items:[{productId,quantity}], remark } */
function createOrder(data) {
  return post('/order', data)
}

/** 模拟支付 */
function payOrder(id) {
  return post('/order/pay/' + id)
}

/** 退订（reason 为 query 参数） */
function cancelOrder(id, reason) {
  return put('/order/cancel/' + id, null, { reason })
}

module.exports = {
  getOrderList,
  getOrderDetail,
  createOrder,
  payOrder,
  cancelOrder
}
