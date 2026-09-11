/**
 * 首页：欢迎区 + 套餐 + 奶品列表
 */
const auth = require('../../utils/auth')
const productApi = require('../../api/product')

Page({
  data: {
    user: null,
    packages: [],
    products: [],
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
        products: (prodRes && prodRes.records) || []
      })
    } catch (e) {
      console.error('加载首页数据失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  goProduct(e) {
    const id = e.currentTarget.dataset.id
    wx.showToast({ title: '奶品详情页开发中', icon: 'none' })
  },

  goPackage(e) {
    const id = e.currentTarget.dataset.id
    wx.showToast({ title: '套餐详情页开发中', icon: 'none' })
  }
})
