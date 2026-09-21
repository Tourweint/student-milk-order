/**
 * 我的退款单：按状态筛选 + 分页加载
 *
 * 退款单状态：1 待审核 → 2 已审核待退款 → 3 已退款；1 待审核 → 4 已拒绝（可重新申请）。
 * 金额与盒数以「执行退款」时刻为准（列表中的金额为已退款单的实际金额，未执行时显示待执行）。
 */
const auth = require('../../utils/auth')
const refundApi = require('../../api/refund')

const TABS = [
  { code: 0, label: '全部' },
  { code: 1, label: '待审核' },
  { code: 2, label: '待退款' },
  { code: 3, label: '已退款' },
  { code: 4, label: '已拒绝' }
]

Page({
  data: {
    tabs: TABS,
    activeStatus: 0,
    refunds: [],
    pageNum: 1,
    pageSize: 10,
    total: 0,
    hasMore: true,
    loading: false
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
    this.setData({ pageNum: 1, refunds: [], hasMore: true, total: 0 })
    this.loadRefunds(true)
  },

  async loadRefunds(reset) {
    if (this.data.loading || (!reset && !this.data.hasMore)) return
    this.setData({ loading: true })
    try {
      const params = { pageNum: this.data.pageNum, pageSize: this.data.pageSize }
      if (this.data.activeStatus > 0) {
        params.status = this.data.activeStatus
      }
      const res = await refundApi.getMyRefunds(params)
      const records = (res && res.list) || []
      this.setData({
        refunds: reset ? records : this.data.refunds.concat(records),
        total: (res && res.total) || 0,
        hasMore: this.data.refunds.length + records.length < ((res && res.total) || 0),
        pageNum: this.data.pageNum + 1
      })
    } catch (e) {
      console.error('加载退款单失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  onReachBottom() {
    this.loadRefunds(false)
  },

  /** 查看退款单详情（无独立详情接口，用弹窗展示已有字段） */
  showDetail(e) {
    const index = Number(e.currentTarget.dataset.index)
    const item = this.data.refunds[index]
    if (!item) return
    const lines = [
      '退款单号：' + item.refundNo,
      '订单号：' + item.orderNo,
      '状态：' + item.statusText,
      '申请盒数：' + (item.applyBoxCount == null ? '—' : item.applyBoxCount) + ' 盒',
      '已退盒数：' + (item.refundedBoxes || 0) + ' 盒',
      '退款金额：' + (item.refundAmount == null ? '待执行' : '¥' + item.refundAmount),
      '申请原因：' + (item.applyReason || '—'),
      '审核意见：' + (item.auditRemark || '—'),
      '申请时间：' + (item.createTime || '—'),
      '退款时间：' + (item.refundTime || '—')
    ]
    wx.showModal({
      title: '退款单详情',
      content: lines.join('\n'),
      showCancel: false,
      confirmText: '知道了'
    })
  },

  /** 去订单列表申请（同一订单已有进行中退款单时会被拒绝） */
  goOrders() {
    wx.navigateTo({ url: '/pages/order-list/order-list' })
  }
})
