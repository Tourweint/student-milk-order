/**
 * 购物车页：奶品清单 + 数量调整 + 删除 + 合计 + 去结算
 * 结算跳转下单页 cart 模式，提交支付成功后清空购物车
 */
const cart = require('../../utils/cart')

Page({
  data: {
    items: [],
    count: 0,
    total: 0
  },

  onShow() {
    this.refresh()
  },

  refresh() {
    this.setData({
      items: cart.getItems(),
      count: cart.getCount(),
      total: cart.getTotal()
    })
  },

  increase(e) {
    const id = Number(e.currentTarget.dataset.id)
    cart.setQuantity(id, this.currentQuantity(id) + 1)
    this.refresh()
  },

  decrease(e) {
    const id = Number(e.currentTarget.dataset.id)
    cart.setQuantity(id, this.currentQuantity(id) - 1)
    this.refresh()
  },

  currentQuantity(id) {
    const item = this.data.items.find((x) => x.productId === id)
    return item ? item.quantity : 0
  },

  removeItem(e) {
    const id = Number(e.currentTarget.dataset.id)
    const item = this.data.items.find((x) => x.productId === id)
    wx.showModal({
      title: '移除奶品',
      content: '确定将「' + (item ? item.name : '该奶品') + '」移出购物车？',
      success: (res) => {
        if (!res.confirm) return
        cart.remove(id)
        this.refresh()
      }
    })
  },

  goSettle() {
    if (!this.data.items.length) {
      wx.showToast({ title: '购物车是空的', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/order-create/order-create?mode=cart' })
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  }
})
