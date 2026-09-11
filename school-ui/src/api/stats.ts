import { get } from '@/utils/request'

/** 仪表盘总览数据 */
export function getDashboard() {
  return get('/stats/dashboard')
}

/** 订单趋势 */
export function getOrderTrend(params: { type?: string; startDate?: string; endDate?: string }) {
  return get('/stats/order/trend', params)
}

/** 奶品品类占比 */
export function getOrderCategory() {
  return get('/stats/order/category')
}

/** 班级订购排行榜 */
export function getClassRanking(limit?: number) {
  return get('/stats/order/class-ranking', { limit })
}

/** 营养摄入仪表盘 */
export function getNutritionDashboard(classId?: number) {
  return get('/stats/nutrition/dashboard', { classId })
}

/** 学生喝奶覆盖率 */
export function getCoverage() {
  return get('/stats/coverage')
}
