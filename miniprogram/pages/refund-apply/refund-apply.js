/**
 * 申请退款页：展示当前可退期次与预估金额，家长填写申请盒数（参考值）与原因后提交。
 *
 * 说明：金额以管理员「执行退款」时刻按可退期次计算，申请盒数仅作参考；
 * 同一订单已有进行中退款单时后端会拒绝。
 */
const auth = require('../../utils/auth')
const refundApi = require('../../api/refund')

Page({
  data: {
    orderId: null,
    preview: null,
    loading: true,
    submitting: false,
    applyBoxCount: '',
    reason: ''
  },

  onLoad(options) {
    this.setData({ orderId: Number(options.orderId) })
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadPreview()
  },

  async loadPreview() {
    this.setData({ loading: true })
    try {
      const preview = await refundApi.getRefundPreview(this.data.orderId)
      const boxes = preview.refundableBoxes || 0
      this.setData({
        preview,
        // 默认按最大可退盒数预填，家长可下调
        applyBoxCount: boxes > 0 ? String(boxes) : ''
      })
    } catch (e) {
      console.error('加载退款预览失败', e)
      this.setData({ preview: null })
    } finally {
      this.setData({ loading: false })
    }
  },

  onBoxInput(e) {
    this.setData({ applyBoxCount: e.detail.value })
  },

  onReasonInput(e) {
    this.setData({ reason: e.detail.value })
  },

  async handleSubmit() {
    const preview = this.data.preview
    if (!preview) return
    if (preview.hasActiveRefund) {
      wx.showToast({ title: '该订单已有进行中的退款单', icon: 'none' })
      return
    }
    const refundable = preview.refundableBoxes || 0
    if (refundable <= 0) {
      wx.showToast({ title: '该订单当前无可退期次', icon: 'none' })
      return
    }
    const boxes = Number(this.data.applyBoxCount)
    if (!boxes || boxes <= 0) {
      wx.showToast({ title: '请填写申请盒数', icon: 'none' })
      return
    }
    if (boxes > refundable) {
      wx.showToast({ title: '最多可退 ' + refundable + ' 盒', icon: 'none' })
      return
    }

    const confirmed = await new Promise((resolve) => {
      wx.showModal({
        title: '确认申请退款',
        content: '当前可退 ' + refundable + ' 盒，预估退款 ¥' + preview.estimatedAmount
          + '。提交后需等待管理员审核并执行，实际退款以执行时刻的期次为准。',
        confirmText: '提交申请',
        success: (res) => resolve(res.confirm)
      })
    })
    if (!confirmed) return

    this.setData({ submitting: true })
    try {
      await refundApi.applyRefund(this.data.orderId, {
        applyBoxCount: boxes,
        reason: this.data.reason || '家长申请按期次退款'
      })
      wx.showToast({ title: '已提交，等待审核', icon: 'success' })
      setTimeout(() => {
        wx.redirectTo({ url: '/pages/refund-list/refund-list' })
      }, 1200)
    } catch (e) {
      // 业务错误（无可退期次/已有进行中退款单）已由 request.js 统一提示
      console.error('申请退款失败', e)
    } finally {
      this.setData({ submitting: false })
    }
  }
})
