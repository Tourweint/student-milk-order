/**
 * 奶品详情页
 */
const auth = require('../../utils/auth')
const productApi = require('../../api/product')

Page({
  data: {
    product: null,
    loading: true
  },

  onLoad(options) {
    this.productId = Number(options.id)
    this.loadDetail()
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
    }
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

  /** 立即订购：跳下单页（奶品模式） */
  goOrder() {
    wx.navigateTo({ url: '/pages/order-create/order-create?mode=product&productId=' + this.productId })
  }
})
