/**
 * 首页：欢迎区 + 套餐 + 奶品列表
 */
const auth = require('../../utils/auth')
const productApi = require('../../api/product')
const deliveryApi = require('../../api/delivery')
const cart = require('../../utils/cart')

Page({
  data: {
    user: null,
    packages: [],
    products: [],
    cartCount: 0,
    loading: true,
    pendingQuantity: 0,
    pendingLoaded: false,
    nextDeliveryDate: '',
    recentRejects: []
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
    // 家长端首页信息（剩余/下次配送日/近期拒收）独立加载：失败不影响首页其他内容
    this.loadParentHome()
  },

  /**
   * 家长端首页聚合：剩余待配送 + 下次配送日 + 近期拒收。
   * 独立加载，失败不得影响首页其余内容，也不得白屏。
   * 兜底约定（勿破坏）：后端不可用 / 家长未绑定时保持 pendingLoaded=false（卡片不渲染），
   * 且不得把取不到值当成 0 显示——否则会出现「已全部配送完成」这种误导性空态。
   */
  async loadParentHome() {
    try {
      const data = await deliveryApi.getParentHome()
      const quantity = data && data.pendingQuantity
      // 只接受非负整数：null/undefined/字符串等一律按失败处理
      // （不能用 Number() 转换——Number(null) === 0 会被静默当成"没有待配送"）
      if (!Number.isInteger(quantity) || quantity < 0) {
        throw new Error('剩余待配送返回值异常：' + quantity)
      }
      this.setData({
        pendingQuantity: quantity,
        pendingLoaded: true,
        nextDeliveryDate: (data && data.nextDeliveryDate) || '',
        recentRejects: (data && data.recentRejects) || []
      })
    } catch (e) {
      console.error('加载家长端首页信息失败', e)
    }
  },

  goProduct(e) {
    const id = Number(e.currentTarget.dataset.id)
    // 列表里已有该奶品数据，先行传给详情页渲染，详情页再自行刷新最新数据
    const product = this.data.products.find((x) => x.id === id)
    wx.navigateTo({
      url: '/pages/product/product?id=' + id,
      success: (res) => {
        if (product) {
          res.eventChannel.emit('productCache', product)
        }
      }
    })
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
    cart.add(product, 1)
    this.setData({ cartCount: cart.getCount() })
    wx.showToast({ title: '已加入购物车', icon: 'success' })
  },

  goCart() {
    wx.navigateTo({ url: '/pages/cart/cart' })
  }
})
