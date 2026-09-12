/**
 * 奶品详情页：数量选择 + 加入购物车 / 立即订购
 */
const auth = require('../../utils/auth')
const productApi = require('../../api/product')
const cart = require('../../utils/cart')

Page({
  data: {
    product: null,
    loading: true,
    buyQty: 1,
    cartCount: 0
  },

  onLoad(options) {
    this.productId = Number(options.id)
    // 优先用列表页传来的数据立即渲染，避免等待网络请求
    const channel = this.getOpenerEventChannel && this.getOpenerEventChannel()
    if (channel && channel.on) {
      channel.on('productCache', (p) => {
        if (p && Number(p.id) === this.productId && !this.data.product) {
          this.setData({ product: p })
        }
      })
    }
    this.loadDetail()
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.setData({ cartCount: cart.getCount() })
  },

  async loadDetail() {
    this.setData({ loading: true })
    try {
      const detail = await productApi.getProductDetail(this.productId)
      this.setData({ product: detail })
    } catch (e) {
      console.error('加载奶品详情失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  // ==================== 数量调整 ====================

  increaseQty() {
    const product = this.data.product
    const max = 99
    if (this.data.buyQty >= max) {
      wx.showToast({ title: '已达库存上限', icon: 'none' })
      return
    }
    this.setData({ buyQty: this.data.buyQty + 1 })
  },

  decreaseQty() {
    if (this.data.buyQty > 1) {
      this.setData({ buyQty: this.data.buyQty - 1 })
    }
  },

  // ==================== 加购 / 下单 ====================

  addToCart() {
    const product = this.data.product
    if (!product || product.status !== 1) return
    cart.add(product, this.data.buyQty, product.quantity)
    this.setData({ cartCount: cart.getCount() })
    wx.showToast({ title: '已加入购物车', icon: 'success' })
  },

  /** 立即订购：携带数量跳下单页（奶品模式） */
  goOrder() {
    wx.navigateTo({
      url: '/pages/order-create/order-create?mode=product&productId=' + this.productId + '&qty=' + this.data.buyQty
    })
  },

  goCart() {
    wx.navigateTo({ url: '/pages/cart/cart' })
  }
})
