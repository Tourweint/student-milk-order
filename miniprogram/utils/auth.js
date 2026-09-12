/**
 * 登录态管理：token 与用户信息存取
 */
const cart = require('./cart')

const TOKEN_KEY = 'token'
const USER_KEY = 'userInfo'
function setToken(token) {
  wx.setStorageSync(TOKEN_KEY, token)
}

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || ''
}

function setUser(userInfo) {
  wx.setStorageSync(USER_KEY, userInfo)
  const app = getApp()
  if (app) {
    app.globalData.userInfo = userInfo
  }
}

function getUser() {
  return wx.getStorageSync(USER_KEY) || null
}

function isLogin() {
  return !!getToken()
}

/** 清理登录态（token + 用户信息 + 购物车） */
function clearLogin() {
  wx.removeStorageSync(TOKEN_KEY)
  wx.removeStorageSync(USER_KEY)
  cart.clear()
  const app = getApp()
  if (app) {
    app.globalData.userInfo = null
  }
}

module.exports = {
  setToken,
  getToken,
  setUser,
  getUser,
  isLogin,
  clearLogin
}
