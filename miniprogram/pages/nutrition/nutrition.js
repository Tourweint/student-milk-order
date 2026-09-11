/**
 * 营养统计：日期范围汇总 + 每日摄入 + 明细列表
 */
const auth = require('../../utils/auth')
const nutritionApi = require('../../api/nutrition')

const pad = (n) => (n < 10 ? '0' + n : '' + n)
function fmtDate(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}
function addDays(base, days) {
  const d = new Date(base)
  d.setDate(d.getDate() + days)
  return d
}

Page({
  data: {
    ranges: [
      { label: '近7天', days: 7 },
      { label: '近30天', days: 30 }
    ],
    activeDays: 7,
    summaryList: [],
    totals: { ml: 0, energy: 0, protein: 0, fat: 0, calcium: 0, count: 0 },
    records: [],
    pageNum: 1,
    pageSize: 10,
    hasMore: true,
    loading: false
  },

  onShow() {
    if (!auth.isLogin()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.reload()
  },

  switchRange(e) {
    const days = Number(e.currentTarget.dataset.days)
    if (days === this.data.activeDays) return
    this.setData({ activeDays: days })
    this.reload()
  },

  /** 计算日期范围：结束今天，开始往前推 */
  calcRange() {
    const end = new Date()
    const start = addDays(end, -(this.data.activeDays - 1))
    return { startDate: fmtDate(start), endDate: fmtDate(end) }
  },

  reload() {
    this.setData({ pageNum: 1, records: [], hasMore: true })
    this.loadData()
  },

  async loadData() {
    if (this.data.loading) return
    this.setData({ loading: true })
    const { startDate, endDate } = this.calcRange()
    try {
      // 汇总 + 明细并行
      const [summary, intakePage] = await Promise.all([
        nutritionApi.getIntakeSummary({ startDate, endDate }),
        nutritionApi.getIntakeList({ pageNum: this.data.pageNum, pageSize: this.data.pageSize, startDate, endDate })
      ])
      const summaryList = summary || []
      const totals = summaryList.reduce(
        (acc, s) => ({
          ml: acc.ml + (s.totalMl || 0),
          energy: acc.energy + Number(s.totalEnergy || 0),
          protein: acc.protein + Number(s.totalProtein || 0),
          fat: acc.fat + Number(s.totalFat || 0),
          calcium: acc.calcium + Number(s.totalCalcium || 0),
          count: acc.count + (s.productCount || 0)
        }),
        { ml: 0, energy: 0, protein: 0, fat: 0, calcium: 0, count: 0 }
      )
      const list = (intakePage && intakePage.list) || []
      this.setData({
        summaryList,
        totals,
        records: this.data.pageNum === 1 ? list : this.data.records.concat(list),
        hasMore: this.data.records.length + list.length < ((intakePage && intakePage.total) || 0),
        pageNum: this.data.pageNum + 1
      })
    } catch (e) {
      console.error('加载营养数据失败', e)
    } finally {
      this.setData({ loading: false })
    }
  },

  onReachBottom() {
    this.loadData()
  }
})
