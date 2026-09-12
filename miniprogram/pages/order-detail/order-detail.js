/**
 * 订单详情页：订单信息 + 明细 + 支付信息 + 操作
 */
const auth = require('../../utils/auth')
const orderApi = require('../../api/order')
const pay = require('../../utils/pay')

Page({
  data: {
    order: null,
    loading: true,
    paying: false
  },

  onLoad(options) {
    this.orderId = Number(options.id)
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadDetail()
  },

  async loadDetail() {
    this.setData({ loading: true })
    try {
      const order = await orderApi.getOrderDetail(this.orderId)
      this.setData({ order })
    } catch (e) {
      console.error('加载订单详情失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  async handlePay() {
    if (this.data.paying) return
    this.setData({ paying: true })
    try {
      const result = await pay.requestWechatPay(this.orderId)
      if (result.paid) {
        wx.showToast({ title: '支付成功', icon: 'success' })
        this.loadDetail()
      } else {
        wx.showToast({ title: result.message, icon: 'none' })
      }
    } catch (e) {
      // 预下单失败等业务错误已由 request.js 统一提示
      console.error('发起支付失败', e)
    } finally {
      this.setData({ paying: false })
    }
  },

  /** 取消支付（待支付）/ 退订（已支付）：均走 cancelOrder，按状态区分文案 */
  handleCancel() {
    const paid = this.data.order && this.data.order.status === 2
    wx.showModal({
      title: paid ? '确认退订' : '取消支付',
      content: paid
        ? '退订后已支付金额将退回（模拟），确认退订该订单？'
        : '订单尚未支付，取消后订单将作废，确认取消支付？',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: paid ? '退订中' : '取消中' })
        try {
          await orderApi.cancelOrder(this.orderId, paid ? '用户申请退订' : '用户取消支付')
          wx.hideLoading()
          wx.showToast({ title: paid ? '已退订' : '已取消', icon: 'success' })
          this.loadDetail()
        } catch (e) {
          wx.hideLoading()
        }
      }
    })
  }
})
