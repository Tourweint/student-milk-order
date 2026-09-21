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
    recentRejects: [],
    exemption: null
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
    // 「当日豁免」名额独立加载：失败即不渲染该卡片（不误导为"可以豁免"）
    this.loadExemption()
  },

  /**
   * 家长端「当日豁免」名额：今天可豁免任务数 + 本月剩余次数。
   * 独立加载、失败不渲染（与 pendingLoaded 同一约定：拿不到值不得当成 0/可用的正向结论）。
   */
  async loadExemption() {
    try {
      const data = await deliveryApi.getParentExemption()
      if (!data || !Number.isInteger(data.exemptableCount) || !Number.isInteger(data.remaining)) {
        throw new Error('豁免名额返回值异常')
      }
      this.setData({ exemption: data })
    } catch (e) {
      console.error('加载当日豁免名额失败', e)
      this.setData({ exemption: null })
    }
  },

  /** 申请当日豁免：二次确认（不可撤销、占用月度次数）后调用 */
  async onExemptToday() {
    const info = this.data.exemption
    if (!info || !info.exemptableCount) {
      wx.showToast({ title: '今天没有可豁免的配送', icon: 'none' })
      return
    }
    const confirm = await new Promise((resolve) => {
      wx.showModal({
        title: '申请当日豁免',
        content: `今天还有 ${info.exemptableCount} 份奶尚未送出，确认今天不要了吗？\n`
          + `取消后不再配送（配额会回补），本月剩余 ${info.remaining} / ${info.monthlyLimit} 次，操作不可撤销。`,
        confirmText: '确认豁免',
        cancelText: '再想想',
        success: (res) => resolve(res.confirm),
        fail: () => resolve(false)
      })
    })
    if (!confirm) return
    try {
      const result = await deliveryApi.exemptToday('家长申请当日豁免')
      wx.showToast({ title: '已豁免今天的配送', icon: 'success' })
      this.setData({ exemption: result })
      // 剩余待配送盒数随之变化，重新拉一次首页聚合
      this.loadParentHome()
    } catch (e) {
      // 业务错误（如本月次数用完、任务已送出）已由 request 统一提示
      console.error('当日豁免失败', e)
      this.loadExemption()
    }
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
