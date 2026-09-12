/**
 * 模拟微信支付流程
 * 真实链路：预下单拿凭证 → wx.requestPayment 唤起微信支付 → 用户确认扣款 → 微信异步回调后端 → 后端更新订单
 * 本地模拟：预下单拿凭证 → 弹"微信支付（模拟）"确认框 → 调模拟微信确认接口 → 后端收到模拟回调更新订单 → 前端轮询支付结果
 */
const orderApi = require('../api/order')
const payApi = require('../api/pay')

const POLL_INTERVAL_MS = 1000
const POLL_MAX_TIMES = 10

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 发起模拟微信支付
 * @param {number} orderId 订单 ID（须为待支付状态）
 * @returns {Promise<{paid: boolean, message?: string}>} paid=false 时 message 说明原因（取消/超时）
 */
async function requestWechatPay(orderId) {
  // 1. 预下单：后端生成预支付单，返回调起凭证
  const params = await payApi.prepayOrder(orderId)

  // 2. 唤起"微信支付"（模拟弹窗，替代 wx.requestPayment）
  const confirmed = await new Promise((resolve) => {
    wx.showModal({
      title: '微信支付（模拟）',
      content: '订单号 ' + params.outTradeNo + '\n支付金额 ¥' + params.amount + '\n\n确认支付？',
      confirmText: '确认支付',
      cancelText: '取消',
      success: (res) => resolve(res.confirm)
    })
  })
  if (!confirmed) {
    return { paid: false, message: '已取消支付，订单保留待支付' }
  }

  // 3. 用户确认扣款：由模拟微信侧受理，触发异步回调后端通知地址
  await payApi.confirmWechatPay(params.prepayId)

  // 4. 轮询订单状态，等待后端收到回调并更新为已支付
  for (let i = 0; i < POLL_MAX_TIMES; i++) {
    await sleep(POLL_INTERVAL_MS)
    try {
      const order = await orderApi.getOrderDetail(orderId)
      if (order.status === 2) {
        return { paid: true }
      }
    } catch (e) {
      // 单次轮询失败（如网络抖动）不中断，继续等待
      console.error('轮询支付结果失败', e)
    }
  }
  return { paid: false, message: '支付结果确认超时，请稍后在订单列表查看' }
}

module.exports = {
  requestWechatPay
}
