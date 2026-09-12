/**
 * 登录/绑定页
 * 流程：wx.login 拿 code → POST /auth/wx-login
 *   - bound=true  → 存 token 进首页
 *   - bound=false → 展示绑定表单（姓名/学号搜索孩子）→ POST /auth/wx-bind → 进首页
 */
const config = require('../../config/index')
const authApi = require('../../api/auth')
const studentApi = require('../../api/student')
const auth = require('../../utils/auth')

Page({
  data: {
    loading: false,
    binding: false,
    showBind: false,
    openid: '',
    form: { realName: '', phone: '', studentNo: '' },
    studentList: [],
    selectedStudent: null,
    searched: false
  },

  onLoad() {
    // 已有登录态直接进首页
    if (auth.isLogin()) {
      wx.reLaunch({ url: '/pages/index/index' })
    }
  },

  /**
   * 获取登录凭证：
   * mock 模式用本地持久化标识（保证 openid 稳定）；真实模式走 wx.login
   */
  getCode() {
    return new Promise((resolve, reject) => {
      if (config.USE_MOCK_WX) {
        let devOpenid = wx.getStorageSync('devOpenid')
        if (!devOpenid) {
          devOpenid = 'dev_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10)
          wx.setStorageSync('devOpenid', devOpenid)
        }
        resolve('mock_' + devOpenid)
        return
      }
      wx.login({
        success: (res) => (res.code ? resolve(res.code) : reject(new Error('wx.login 失败'))),
        fail: () => reject(new Error('wx.login 失败'))
      })
    })
  },

  async handleWxLogin() {
    if (this.data.loading) return
    this.setData({ loading: true })
    try {
      const code = await this.getCode()
      const res = await authApi.wxLogin(code)
      if (res.bound) {
        this.saveLogin(res)
        wx.reLaunch({ url: '/pages/index/index' })
      } else {
        // 未绑定 → 展示绑定表单
        this.setData({ showBind: true, openid: res.openid })
      }
    } catch (e) {
      console.error('wx-login 失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  saveLogin(res) {
    auth.setToken(res.token)
    auth.setUser({
      userId: res.userId,
      username: res.username,
      realName: res.realName,
      roles: res.roles || []
    })
  },

  async searchStudent() {
    const studentNo = (this.data.form.studentNo || '').trim()
    if (!studentNo) {
      wx.showToast({ title: '请输入学号', icon: 'none' })
      return
    }
    wx.showLoading({ title: '搜索中' })
    try {
      const res = await studentApi.searchStudents(studentNo)
      this.setData({ studentList: res || [], searched: true })
    } catch (e) {
      console.error('搜索学生失败', e)
    } finally {
      wx.hideLoading()
    }
  },

  selectStudent(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.studentList.find((s) => s.id === id)
    if (!item) return
    // 选中前二次确认，防止学号相近时点错孩子
    const content = item.parentName
      ? '学生：' + item.studentName + '（' + item.className + '）\n系统登记家长：' + item.parentName
      : '学生：' + item.studentName + '（' + item.className + '）\n系统未登记家长姓名，请自行核对'
    wx.showModal({
      title: '确认是您的孩子吗',
      content,
      confirmText: '确认',
      cancelText: '再想想',
      success: (res) => {
        this.setData({ selectedStudent: res.confirm ? item : null })
      }
    })
  },

  async handleBind() {
    const form = this.data.form
    if (!form.realName.trim()) {
      wx.showToast({ title: '请填写家长姓名', icon: 'none' })
      return
    }
    if (!this.data.selectedStudent) {
      wx.showToast({ title: '请搜索并选择孩子', icon: 'none' })
      return
    }
    this.setData({ binding: true })
    try {
      // 提交前重新获取凭证，防止 wx.login 的 code 过期
      const code = await this.getCode()
      const res = await authApi.wxBind({
        code,
        realName: form.realName.trim(),
        phone: form.phone.trim(),
        studentId: this.data.selectedStudent.id
      })
      this.saveLogin(res)
      wx.showToast({ title: '绑定成功', icon: 'success' })
      setTimeout(() => wx.reLaunch({ url: '/pages/index/index' }), 600)
    } catch (e) {
      console.error('wx-bind 失败', e)
    } finally {
      this.setData({ binding: false })
    }
  },

  onRealNameInput(e) {
    this.setData({ 'form.realName': e.detail.value })
  },
  onPhoneInput(e) {
    this.setData({ 'form.phone': e.detail.value })
  },
  onStudentNoInput(e) {
    this.setData({ 'form.studentNo': e.detail.value })
  }
})
