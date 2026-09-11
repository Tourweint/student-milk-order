/**
 * 配送记录：家长查看孩子奶品配送与签收状态
 */
const auth = require('../../utils/auth')
const deliveryApi = require('../../api/delivery')

Page({
  data: {
    records: [],
    pageNum: 1,
    pageSize: 10,
    total: 0,
    hasMore: true,
    loading: false
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.reload()
  },

  reload() {
    this.setData({ pageNum: 1, records: [], hasMore: true, total: 0 })
    this.loadRecords(true)
  },

  async loadRecords(reset) {
    if (this.data.loading || (!reset && !this.data.hasMore)) return
    this.setData({ loading: true })
    try {
      const res = await deliveryApi.getRecordList({
        pageNum: this.data.pageNum,
        pageSize: this.data.pageSize
      })
      const list = (res && res.list) || []
      this.setData({
        records: reset ? list : this.data.records.concat(list),
        total: (res && res.total) || 0,
        hasMore: this.data.records.length + list.length < ((res && res.total) || 0),
        pageNum: this.data.pageNum + 1
      })
    } catch (e) {
      console.error('加载配送记录失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  onReachBottom() {
    this.loadRecords(false)
  }
})
