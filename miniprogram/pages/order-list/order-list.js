/**
 * 我的订单：状态筛选 + 分页 + 去支付/退订
 */
const auth = require('../../utils/auth')
const orderApi = require('../../api/order')
const pay = require('../../utils/pay')

const TABS = [
  { code: 0, label: '全部' },
  { code: 1, label: '待支付' },
  { code: 2, label: '已支付' },
  { code: 3, label: '配送中' },
  { code: 4, label: '已完成' },
  { code: 5, label: '已退订' }
]

Page({
  data: {
    tabs: TABS,
    activeStatus: 0,
    orders: [],
    pageNum: 1,
    pageSize: 10,
    total: 0,
    hasMore: true,
    loading: false,
    paying: null
  },

  onLoad(options) {
    const status = Number(options.status || 0)
    this.setData({ activeStatus: status })
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.reload()
  },

  switchTab(e) {
    const code = Number(e.currentTarget.dataset.code)
    if (code === this.data.activeStatus) return
    this.setData({ activeStatus: code })
    this.reload()
  },

  reload() {
    this.setData({ pageNum: 1, orders: [], hasMore: true, total: 0 })
    this.loadOrders(true)
  },

  async loadOrders(reset) {
    if (this.data.loading || (!reset && !this.data.hasMore)) return
    this.setData({ loading: true })
    try {
      const params = { pageNum: this.data.pageNum, pageSize: this.data.pageSize }
      if (this.data.activeStatus > 0) {
        params.status = this.data.activeStatus
      }
      const res = await orderApi.getOrderList(params)
      const records = (res && res.list) || []
      this.setData({
        orders: reset ? records : this.data.orders.concat(records),
        total: (res && res.total) || 0,
        hasMore: this.data.orders.length + records.length < ((res && res.total) || 0),
        pageNum: this.data.pageNum + 1
      })
    } catch (e) {
      console.error('加载订单失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  onReachBottom() {
    this.loadOrders(false)
  },

  /** 查看订单详情 */
  goDetail(e) {
    const id = Number(e.currentTarget.dataset.id)
    wx.navigateTo({ url: '/pages/order-detail/order-detail?id=' + id })
  },

  /** 申请退款（按期次退款）：进入后可预览可退期次与预估金额 */
  goRefundApply(e) {
    const id = Number(e.currentTarget.dataset.id)
    wx.navigateTo({ url: '/pages/refund-apply/refund-apply?orderId=' + id })
  },

  /** 去支付（模拟微信支付） */
  async handlePay(e) {
    const id = Number(e.currentTarget.dataset.id)
    this.setData({ paying: id })
    try {
      const result = await pay.requestWechatPay(id)
      if (result.paid) {
        wx.showToast({ title: '支付成功', icon: 'success' })
        this.reload()
      } else {
        wx.showToast({ title: result.message, icon: 'none' })
      }
    } catch (err) {
      // 预下单失败等业务错误已由 request.js 统一提示
      console.error('发起支付失败', err)
    } finally {
      this.setData({ paying: null })
    }
  },

  /** 取消支付（待支付）/ 退订（已支付）：均走 cancelOrder，按订单状态区分文案 */
  handleCancel(e) {
    const id = Number(e.currentTarget.dataset.id)
    const order = this.data.orders.find((x) => x.id === id)
    const paid = order && order.status === 2
    wx.showModal({
      title: paid ? '确认退订' : '取消支付',
      content: paid
        ? '退订后已支付金额将退回（模拟），确认退订该订单？'
        : '订单尚未支付，取消后订单将作废，确认取消支付？',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: paid ? '退订中' : '取消中' })
        try {
          await orderApi.cancelOrder(id, paid ? '用户申请退订' : '用户取消支付')
          wx.hideLoading()
          wx.showToast({ title: paid ? '已退订' : '已取消', icon: 'success' })
          this.reload()
        } catch (err) {
          wx.hideLoading()
        }
      }
    })
  }
})
