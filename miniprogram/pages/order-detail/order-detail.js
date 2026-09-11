/**
 * 订单详情页：订单信息 + 明细 + 支付信息 + 操作
 */
const auth = require('../../utils/auth')
const orderApi = require('../../api/order')

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
    wx.showLoading({ title: '支付中' })
    try {
      await orderApi.payOrder(this.orderId)
      wx.hideLoading()
      wx.showToast({ title: '支付成功', icon: 'success' })
      this.loadDetail()
    } catch (e) {
      wx.hideLoading()
    } finally {
      this.setData({ paying: false })
    }
  },

  handleCancel() {
    wx.showModal({
      title: '确认退订',
      content: '退订后已支付金额将退回（模拟），确认退订该订单？',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '退订中' })
        try {
          await orderApi.cancelOrder(this.orderId, '用户申请退订')
          wx.hideLoading()
          wx.showToast({ title: '已退订', icon: 'success' })
          this.loadDetail()
        } catch (e) {
          wx.hideLoading()
        }
      }
    })
  }
})
