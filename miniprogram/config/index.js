/**
 * 全局配置
 *
 * 开发环境：后端跑在本机，用 localhost
 * 真机调试：手机与电脑同一 Wi-Fi，改为电脑局域网 IP，如 http://192.168.1.100:8090/api
 * 上线：改为已备案的 HTTPS 域名，如 https://api.example.com/api
 *
 * USE_MOCK_WX：本地联调时后端 wechat.mock-enabled=true（无真实小程序凭据）
 * - true：wx.login 改用本地持久化的模拟标识，保证同一设备 openid 稳定（否则每次 code 不同会永远“未绑定”）
 * - false：走真实 wx.login，配合后端配置的真实 appid/secret
 */
module.exports = {
  BASE_URL: 'http://localhost:8090/api',
  USE_MOCK_WX: true
}
