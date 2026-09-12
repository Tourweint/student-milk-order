/**
 * 下单页
 * 进入方式：
 *   mode=product&productId=x   奶品直购（单奶品 + 数量）
 *   mode=package&packageId=x   套餐订购（套餐价 + 勾选奶品明细）
 *   mode=cart                  购物车结算（多奶品明细，支付成功后清空购物车）
 * 提交：createOrder → 模拟微信支付（预下单→确认→回调）→ 跳订单列表
 */
const auth = require('../../utils/auth')
const authApi = require('../../api/auth')
const productApi = require('../../api/product')
const orderApi = require('../../api/order')
const cart = require('../../utils/cart')
const pay = require('../../utils/pay')

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
    this.mode = options.mode === 'package' ? 'package'
      : options.mode === 'cart' ? 'cart' : 'product'
    this.targetId = Number(options.productId || options.packageId || 0)
    this.initQty = Number(options.qty) > 0 ? Number(options.qty) : 1
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
        items = [{ productId: p.id, name: p.productName, spec: p.spec, price: p.price, quantity: this.initQty }]
        this.setData({ product: p, student: me, items, total: this.calcTotal(items) })
      } else if (this.mode === 'cart') {
        items = cart.getItems()
        if (!items.length) {
          wx.showToast({ title: '购物车是空的', icon: 'none' })
          setTimeout(() => wx.navigateBack(), 600)
          return
        }
        this.setData({ student: me, items, total: this.calcTotal(items) })
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
    this.setData({ items, total: this.calcTotal(items) })
  },

  decrease(e) {
    const index = Number(e.currentTarget.dataset.index)
    const items = this.data.items.slice()
    if (items[index].quantity > 1) {
      items[index].quantity -= 1
    } else if (this.data.mode === 'package' || this.data.mode === 'cart') {
      // 套餐/购物车模式：减到 0 表示移除该奶品（提交时过滤）
      items[index].quantity = 0
    }
    this.setData({ items, total: this.calcTotal(items) })
  },

  /** package 模式：从可选奶品加入明细 */
  addProduct(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.productList.find((x) => x.id === id)
    if (!p) return
    const items = this.data.items.slice()
    const exists = items.find((x) => x.productId === id)
    if (exists) {
      exists.quantity += 1
    } else {
      items.push({ productId: p.id, name: p.productName, spec: p.spec, price: p.price, quantity: 1 })
    }
    this.setData({ items, total: this.calcTotal(items) })
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

  /**
   * 合计金额（前端展示用）
   * @param {Array} [items] 参与计算的明细；不传时取 this.data.items。
   *   setData 前计算合计必须传入最新明细，否则会用到旧数据（购物车结算页合计显示 0 的原因）
   * 金额按分（整数）累加再转回元，避免浮点误差出现 70.0000000001 一类展示
   */
  calcTotal(items) {
    items = items || this.data.items
    if (this.data.mode === 'package' && this.data.package) {
      return Number(this.data.package.discountPrice || this.data.package.originalPrice || 0)
    }
    const cents = items.reduce(
      (sum, x) => sum + Math.round(Number(x.price) * 100) * x.quantity, 0)
    return cents / 100
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
      // 购物车下单成功即清空购物车（奶品已进入订单，避免残留重复下单）
      if (this.mode === 'cart') {
        cart.clear()
      }
      // 模拟微信支付：预下单 → 确认扣款 → 微信异步回调后端 → 轮询结果
      const result = await pay.requestWechatPay(orderId)
      if (result.paid) {
        wx.showToast({ title: '下单并支付成功', icon: 'success' })
      } else {
        wx.showToast({ title: result.message, icon: 'none' })
      }
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
