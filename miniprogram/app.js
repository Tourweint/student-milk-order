// 学生奶订购小程序 - 全局入口
App({
  globalData: {
    // 当前登录用户信息（登录后由 login/mine 页写入）
    userInfo: null
  },

  onLaunch() {
    // 启动基础初始化；登录态检查由 login 页和各页面 onShow 负责
    console.log('[app] onLaunch')
  }
})
