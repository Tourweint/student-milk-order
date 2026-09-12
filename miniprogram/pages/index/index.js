/**
 * 首页：欢迎区 + 套餐 + 奶品列表
 */
const auth = require('../../utils/auth')
const productApi = require('../../api/product')
const cart = require('../../utils/cart')

Page({
  data: {
    user: null,
    packages: [],
    products: [],
    cartCount: 0,
    loading: true
  },

  onShow() {
    // 登录守卫
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const [pkgRes, prodRes] = await Promise.all([
        productApi.getPackageList(),
        productApi.getProductList({ pageNum: 1, pageSize: 20 })
      ])
      this.setData({
        user: auth.getUser(),
        packages: pkgRes || [],
        products: (prodRes && prodRes.list) || [],
        cartCount: cart.getCount()
      })
    } catch (e) {
      console.error('加载首页数据失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  goProduct(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/product/product?id=' + id })
  },

  goPackage(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/order-create/order-create?mode=package&packageId=' + id })
  },

  /** 快速加购（不跳转，catchtap 阻止冒泡到进详情页） */
  addCart(e) {
    const id = Number(e.currentTarget.dataset.id)
    const product = this.data.products.find((x) => x.id === id)
    if (!product || product.status !== 1) {
      wx.showToast({ title: '该奶品已下架', icon: 'none' })
      return
    }
    cart.add(product, 1, product.quantity)
    this.setData({ cartCount: cart.getCount() })
    wx.showToast({ title: '已加入购物车', icon: 'success' })
  },

  goCart() {
    wx.navigateTo({ url: '/pages/cart/cart' })
  }
})
