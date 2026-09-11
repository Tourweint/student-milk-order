/**
 * 下单页
 * 进入方式：
 *   mode=product&productId=x   奶品直购（单奶品 + 数量）
 *   mode=package&packageId=x   套餐订购（套餐价 + 勾选奶品明细）
 * 提交：createOrder → payOrder（模拟支付）→ 跳订单列表
 */
const auth = require('../../utils/auth')
const authApi = require('../../api/auth')
const productApi = require('../../api/product')
const orderApi = require('../../api/order')

const pad = (n) => (n < 10 ? '0' + n : '' + n)
function fmtDate(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}
function addDays(base, days) {
  const d = new Date(base)
  d.setDate(d.getDate() + days)
  return d
}

Page({
  data: {
    mode: 'product',
    student: null,
    product: null,
    package: null,
    productList: [],        // package 模式可选奶品
    items: [],              // {productId, name, spec, price, quantity}
    total: 0,
    startDate: '',
    endDate: '',
    remark: '',
    submitting: false
  },

  onLoad(options) {
    this.mode = options.mode === 'package' ? 'package' : 'product'
    this.targetId = Number(options.productId || options.packageId || 0)
    const today = new Date()
    const start = addDays(today, 1)
    this.setData({
      mode: this.mode,
      startDate: fmtDate(start),
      endDate: fmtDate(addDays(start, 30))
    })
    this.init()
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
    }
  },

  async init() {
    wx.showLoading({ title: '加载中' })
    try {
      // 1. 当前家长绑定的孩子
      const me = await authApi.getMe()
      if (!me.studentId) {
        wx.showToast({ title: '请先在登录页绑定孩子', icon: 'none' })
        return
      }
      // 2. 按模式加载商品
      let items = []
      if (this.mode === 'product') {
        const p = await productApi.getProductDetail(this.targetId)
        items = [{ productId: p.id, name: p.productName, spec: p.spec, price: p.price, quantity: 1 }]
        this.setData({ product: p, student: me, items, total: this.calcTotal() })
      } else {
        const [pkg, prodRes] = await Promise.all([
          productApi.getPackageDetail(this.targetId),
          productApi.getProductList({ pageNum: 1, pageSize: 50 })
        ])
        this.setData({
          package: pkg,
          student: me,
          productList: (prodRes && prodRes.list) || [],
          items,
          total: this.calcTotal()
        })
      }
    } catch (e) {
      console.error('下单页初始化失败', e)
    } finally {
      wx.hideLoading()
    }
  },

  // ==================== 数量调整 ====================

  increase(e) {
    const index = Number(e.currentTarget.dataset.index)
    const items = this.data.items.slice()
    items[index].quantity += 1
    this.setData({ items, total: this.calcTotal() })
  },

  decrease(e) {
    const index = Number(e.currentTarget.dataset.index)
    const items = this.data.items.slice()
    if (items[index].quantity > 1) {
      items[index].quantity -= 1
    } else if (this.data.mode === 'package') {
      items[index].quantity = 0
    }
    this.setData({ items, total: this.calcTotal() })
  },

  /** package 模式：从可选奶品加入明细 */
  addProduct(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.productList.find((x) => x.id === id)
    if (!p) return
    const exists = this.data.items.find((x) => x.productId === id)
    if (exists) {
      exists.quantity += 1
      this.setData({ items: this.data.items.slice(), total: this.calcTotal() })
    } else {
      this.setData({
        items: this.data.items.concat([{ productId: p.id, name: p.productName, spec: p.spec, price: p.price, quantity: 1 }]),
        total: this.calcTotal()
      })
    }
  },

  // ==================== 日期 ====================

  onStartChange(e) {
    const start = e.detail.value
    const end = this.data.endDate
    this.setData({ startDate: start, endDate: start > end ? start : end, total: this.calcTotal() })
  },

  onEndChange(e) {
    this.setData({ endDate: e.detail.value, total: this.calcTotal() })
  },

  onRemarkInput(e) {
    this.setData({ remark: e.detail.value })
  },

  // ==================== 合计 ====================

  calcTotal() {
    if (this.data.mode === 'package' && this.data.package) {
      return Number(this.data.package.discountPrice || this.data.package.originalPrice || 0)
    }
    return this.data.items.reduce((sum, x) => sum + Number(x.price) * x.quantity, 0)
  },

  // ==================== 提交 ====================

  async handleSubmit() {
    if (this.data.submitting) return
    const items = this.data.items.filter((x) => x.quantity > 0)
    if (!items.length) {
      wx.showToast({ title: '请选择至少一种奶品', icon: 'none' })
      return
    }
    if (!this.data.startDate || !this.data.endDate) {
      wx.showToast({ title: '请选择配送日期', icon: 'none' })
      return
    }
    if (this.data.endDate < this.data.startDate) {
      wx.showToast({ title: '结束日期不能早于开始日期', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    wx.showLoading({ title: '提交订单中' })
    try {
      const orderId = await orderApi.createOrder({
        studentId: this.data.student.studentId,
        packageId: this.data.mode === 'package' ? this.data.package.id : null,
        deliveryStartDate: this.data.startDate,
        deliveryEndDate: this.data.endDate,
        items: items.map((x) => ({ productId: x.productId, quantity: x.quantity })),
        remark: this.data.remark
      })
      // 模拟支付
      wx.showLoading({ title: '支付中' })
      await orderApi.payOrder(orderId)
      wx.hideLoading()
      wx.showToast({ title: '下单并支付成功', icon: 'success' })
      setTimeout(() => {
        wx.redirectTo({ url: '/pages/order-list/order-list?status=1' })
      }, 800)
    } catch (e) {
      wx.hideLoading()
      console.error('下单失败', e)
    } finally {
      this.setData({ submitting: false })
    }
  }
})
