/**
 * 支付接口（模拟微信支付链路）
 */
const { post } = require('../utils/request')

/** 微信支付预下单（模拟）：返回调起支付所需的凭证参数（含 prepayId、金额、订单号） */
function prepayOrder(id) {
  return post('/order/prepay/' + id)
}

/** 模拟用户在微信支付弹窗中确认扣款：微信侧受理后异步回调后端通知地址 */
function confirmWechatPay(prepayId) {
  return post('/mock/wechat/pay-confirm', { prepayId })
}

module.exports = {
  prepayOrder,
  confirmWechatPay
}
