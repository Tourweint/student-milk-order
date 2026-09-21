/**
 * 退款接口（家长侧）：按期次退款
 *
 * 口径（见 docs/基线文档/接口文档.md §10）：
 * - 可退期次 = 待配送期次 ∪ 缺货取消期次（已配送/已完成/真拒收不退）；
 * - 金额以「执行退款」时刻计算，申请时填的盒数只是参考；
 * - 同一订单同时只允许一张进行中退款单（待审核/已审核待退款）。
 */
const { get, post } = require('../utils/request')

/** 本人（绑定学生）退款单分页：{ pageNum, pageSize, status } */
function getMyRefunds(params) {
  return get('/refund/order/my', params)
}

/** 退款预览（只读，不落库）：可退期次明细 + 可退盒数 + 预估金额 */
function getRefundPreview(orderId) {
  return get('/refund/order/' + orderId + '/preview')
}

/** 申请退款：{ applyBoxCount, reason } */
function applyRefund(orderId, data) {
  return post('/refund/order/' + orderId, data)
}

module.exports = {
  getMyRefunds,
  getRefundPreview,
  applyRefund
}
