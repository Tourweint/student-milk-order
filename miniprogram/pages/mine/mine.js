/**
 * 我的页：用户信息 + 功能入口 + 退出登录
 */
const auth = require('../../utils/auth')
const authApi = require('../../api/auth')

Page({
  data: {
    user: null,
    roleText: ''
  },

  onShow() {
    // 登录守卫
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    const local = auth.getUser()
    this.setData({ user: local, roleText: this.roleText(local) })
    this.refreshUser()
  },

  /** 拉取最新用户信息（后端为准） */
  async refreshUser() {
    try {
      const me = await authApi.getMe()
      if (me) {
        auth.setUser({
          userId: me.userId,
          username: me.username,
          realName: me.realName,
          roles: me.roles || []
        })
        this.setData({ user: me, roleText: this.roleText(me) })
      }
    } catch (e) {
      console.error('获取用户信息失败', e)
    }
  },

  roleText(u) {
    if (!u) return ''
    const roles = u.roles || []
    if (roles.indexOf('PARENT') >= 0) return '家长'
    if (roles.indexOf('TEACHER') >= 0) return '班主任'
    if (roles.indexOf('ADMIN') >= 0) return '管理员'
    return (roles[0] || '').toLowerCase()
  },

  /** 功能入口（后续页面） */
  goPage(e) {
    const name = e.currentTarget.dataset.name
    wx.showToast({ title: name + ' 开发中', icon: 'none' })
  },

  logout() {
    wx.showModal({
      title: '提示',
      content: '确认退出登录？',
      success: (res) => {
        if (res.confirm) {
          auth.clearLogin()
          wx.reLaunch({ url: '/pages/login/login' })
        }
      }
    })
  }
})
