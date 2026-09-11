/**
 * 自动续订：计划列表 + 开启续订（选套餐+原订单）+ 关闭 + 手动续订
 */
const auth = require('../../utils/auth')
const authApi = require('../../api/auth')
const productApi = require('../../api/product')
const orderApi = require('../../api/order')
const subscriptionApi = require('../../api/subscription')

Page({
  data: {
    plans: [],
    pageNum: 1,
    pageSize: 10,
    hasMore: true,
    loading: false,

    // 创建表单
    showCreate: false,
    student: null,
    packages: [],
    packageIndex: 0,
    paidOrders: [],
    orderIndex: 0,
    remark: '',
    creating: false
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.reload()
  },

  reload() {
    this.setData({ pageNum: 1, plans: [], hasMore: true })
    this.loadPlans(true)
  },

  async loadPlans(reset) {
    if (this.data.loading || (!reset && !this.data.hasMore)) return
    this.setData({ loading: true })
    try {
      const res = await subscriptionApi.getPlanList({
        pageNum: this.data.pageNum,
        pageSize: this.data.pageSize
      })
      const list = (res && res.list) || []
      this.setData({
        plans: reset ? list : this.data.plans.concat(list),
        hasMore: this.data.plans.length + list.length < ((res && res.total) || 0),
        pageNum: this.data.pageNum + 1
      })
    } catch (e) {
      console.error('加载续订计划失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  onReachBottom() {
    this.loadPlans(false)
  },

  // ==================== 开启续订 ====================

  async openCreate() {
    wx.showLoading({ title: '加载中' })
    try {
      const [me, pkgRes, orderRes] = await Promise.all([
        authApi.getMe(),
        productApi.getPackageList(),
        orderApi.getOrderList({ pageNum: 1, pageSize: 50, status: 2 })
      ])
      const packages = pkgRes || []
      const paidOrders = ((orderRes && orderRes.list) || []).map((o) => ({
        id: o.id,
        orderNo: o.orderNo,
        packageName: o.packageName || '奶品订单',
        payAmount: o.payAmount
      }))
      if (!me.studentId) {
        wx.showToast({ title: '请先在登录页绑定孩子', icon: 'none' })
        return
      }
      if (!paidOrders.length) {
        wx.showToast({ title: '暂无已支付订单，请先下单', icon: 'none' })
        return
      }
      this.setData({
        showCreate: true,
        student: me,
        packages,
        packageIndex: 0,
        paidOrders,
        orderIndex: 0,
        remark: ''
      })
    } catch (e) {
      console.error('加载续订表单数据失败', e)
    } finally {
      wx.hideLoading()
    }
  },

  closeCreate() {
    this.setData({ showCreate: false })
  },

  onPkgChange(e) {
    this.setData({ packageIndex: Number(e.detail.value) })
  },

  onOrderChange(e) {
    this.setData({ orderIndex: Number(e.detail.value) })
  },

  onRemarkInput(e) {
    this.setData({ remark: e.detail.value })
  },

  async submitCreate() {
    if (this.data.creating) return
    const pkg = this.data.packages[this.data.packageIndex]
    const order = this.data.paidOrders[this.data.orderIndex]
    if (!pkg || !order) {
      wx.showToast({ title: '请选择套餐和原订单', icon: 'none' })
      return
    }
    this.setData({ creating: true })
    wx.showLoading({ title: '开启中' })
    try {
      await subscriptionApi.createPlan({
        studentId: this.data.student.studentId,
        packageId: pkg.id,
        originalOrderId: order.id,
        cycleType: 1,
        remark: this.data.remark
      })
      wx.hideLoading()
      wx.showToast({ title: '续订已开启', icon: 'success' })
      this.setData({ showCreate: false })
      this.reload()
    } catch (e) {
      wx.hideLoading()
    } finally {
      this.setData({ creating: false })
    }
  },

  // ==================== 关闭 / 手动续订 ====================

  handleClose(e) {
    const id = Number(e.currentTarget.dataset.id)
    wx.showModal({
      title: '确认关闭',
      content: '关闭后该计划将不再自动续订，确认关闭？',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '关闭中' })
        try {
          await subscriptionApi.closePlan(id)
          wx.hideLoading()
          wx.showToast({ title: '已关闭', icon: 'success' })
          this.reload()
        } catch (err) {
          wx.hideLoading()
        }
      }
    })
  },

  handleTrigger(e) {
    const id = Number(e.currentTarget.dataset.id)
    wx.showModal({
      title: '立即续订',
      content: '将按原订单内容生成新订单并自动支付（模拟），确认？',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '续订中' })
        try {
          await subscriptionApi.triggerRenewal(id)
          wx.hideLoading()
          wx.showToast({ title: '续订成功，可查看新订单', icon: 'success' })
          this.reload()
        } catch (err) {
          wx.hideLoading()
        }
      }
    })
  }
})
