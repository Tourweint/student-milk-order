/**
 * 认证接口
 */
const { get, post } = require('../utils/request')

/** 微信登录：code 换 openid；已绑定返回 bound=true + token，未绑定 bound=false */
function wxLogin(code) {
  return post('/auth/wx-login', { code })
}

/** 微信绑定：绑定已有账号或自动创建家长账号，返回 bound=true + token */
function wxBind(data) {
  return post('/auth/wx-bind', data)
}

/** 获取当前登录用户信息 */
function getMe() {
  return get('/auth/me')
}

module.exports = {
  wxLogin,
  wxBind,
  getMe
}
