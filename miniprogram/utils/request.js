/**
 * 请求封装：统一 wx.request
 * - 自动拼接 BASE_URL、注入 Bearer token
 * - 响应 code !== 200 统一提示；401 清登录态并跳登录页
 */
const config = require('../config/index')
const auth = require('./auth')

function request(options) {
  return new Promise((resolve, reject) => {
    const token = auth.getToken()
    // 拼接 query 参数（如退订 reason）
    let url = options.url
    if (options.query) {
      const qs = Object.keys(options.query)
        .filter((k) => options.query[k] !== undefined && options.query[k] !== null && options.query[k] !== '')
        .map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(options.query[k]))
        .join('&')
      if (qs) {
        url += (url.indexOf('?') >= 0 ? '&' : '?') + qs
      }
    }
    wx.request({
      url: config.BASE_URL + url,
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      success(res) {
        const body = res.data
        if (body && body.code === 200) {
          resolve(body.data)
          return
        }
        // 业务错误
        const message = (body && body.message) || '请求失败'
        if (body && body.code === 401) {
          auth.clearLogin()
          wx.showToast({ title: message || '登录已过期，请重新登录', icon: 'none' })
          wx.reLaunch({ url: '/pages/login/login' })
        } else {
          wx.showToast({ title: message, icon: 'none' })
        }
        reject(body || { code: -1, message })
      },
      fail(err) {
        wx.showToast({ title: '网络请求失败，请检查后端是否启动', icon: 'none' })
        reject(err)
      }
    })
  })
}

module.exports = {
  request,
  get(url, data) {
    return request({ url, method: 'GET', data })
  },
  post(url, data) {
    return request({ url, method: 'POST', data })
  },
  put(url, data, query) {
    return request({ url, method: 'PUT', data, query })
  },
  del(url, data) {
    return request({ url, method: 'DELETE', data })
  }
}
