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
function addMonths(base, months) {
  const d = new Date(base)
  d.setMonth(d.getMonth() + months)
  return d
}

Page({
  data: {
    mode: 'product',
    student: null,
    product: null,
    package: null,
    items: [],              // {productId, name, spec, price, quantity}；套餐模式下为服务端配置的固定明细（只读）
    total: 0,
    startDate: '',
    endDate: '',
    quotaRemaining: null,
    remark: '',
    submitting: false
  },

  onLoad(options) {
    this.mode = options.mode === 'package' ? 'package'
      : options.mode === 'cart' ? 'cart' : 'product'
    this.targetId = Number(options.productId || options.packageId || 0)
    this.initQty = Number(options.qty) > 0 ? Number(options.qty) : 1
    const tomorrow = fmtDate(addDays(new Date(), 1))
    // 散订（奶品直购/购物车）为一次性配送：起止同日，按盒数展开一个配送任务；
    // 套餐（月度/学期）为周期配送：起止区间内每日配送，默认区间在加载套餐后按套餐类型填充
    this.setData({
      mode: this.mode,
      startDate: tomorrow,
      endDate: tomorrow
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
        // 套餐内容固定（服务端配置为准），家长不可自选奶品
        const pkg = await productApi.getPackageDetail(this.targetId)
        const pkgItems = ((pkg && pkg.items) || []).map((it) => ({
          productId: it.productId,
          name: it.productName,
          spec: it.spec,
          quantity: it.quantity
        }))
        // 套餐配送区间固定：月度=1个月，学期=约5个月，家长不可修改
        const start = addDays(new Date(), 1)
        const months = pkg && pkg.packageType === 2 ? 5 : 1
        const end = addDays(addMonths(start, months), -1)
        // 合计直接取套餐价；注意 setData 前不能依赖 this.data.package（尚未生效）
        const total = Number(pkg.discountPrice || pkg.originalPrice || 0)
        this.setData({
          package: pkg,
          student: me,
          items: pkgItems,
          total,
          startDate: fmtDate(start),
          endDate: fmtDate(end)
        })
      }
      // 散订模式展示所选日期机动余量
      this.loadQuotaRemaining()
    } catch (e) {
      console.error('下单页初始化失败', e)
    } finally {
      wx.hideLoading()
    }
  },

  // ==================== 数量调整（散订专用；套餐明细固定不可调整） ====================

  increase(e) {
    if (this.mode === 'package') return
    const index = Number(e.currentTarget.dataset.index)
    const items = this.data.items.slice()
    items[index].quantity += 1
    this.setData({ items, total: this.calcTotal(items) })
  },

  decrease(e) {
    if (this.mode === 'package') return
    const index = Number(e.currentTarget.dataset.index)
    const items = this.data.items.slice()
    if (items[index].quantity > 1) {
      items[index].quantity -= 1
      this.setData({ items, total: this.calcTotal(items) })
    } else if (this.data.mode === 'cart') {
      // 购物车模式：减到 0 表示移除该奶品（提交时过滤）
      items[index].quantity = 0
      this.setData({ items, total: this.calcTotal(items) })
    }
  },

  // ==================== 日期 ====================

  onStartChange(e) {
    // 仅散订可改配送日期（起止同日）；套餐配送周期固定，由页面只读展示
    const start = e.detail.value
    this.setData({ startDate: start, endDate: start })
    this.loadQuotaRemaining()
  },

  /** 散订模式：查询所选日期的机动配额余量 */
  async loadQuotaRemaining() {
    if (this.mode === 'package' || !this.data.startDate) return
    try {
      const remaining = await productApi.getQuotaRemaining(this.data.startDate)
      this.setData({ quotaRemaining: remaining == null ? null : Number(remaining) })
    } catch (e) {
      this.setData({ quotaRemaining: null })
    }
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
