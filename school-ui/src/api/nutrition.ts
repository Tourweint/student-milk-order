import { get, post } from '@/utils/request'

/** 营养成分列表（回填奶品名） */
export function getNutritionInfoList() {
  return get('/nutrition/info/list')
}

/** 某奶品的营养成分 */
export function getNutritionInfoByProductId(productId: number) {
  return get(`/nutrition/info/${productId}`)
}

/** 新增/更新营养成分（按 productId 幂等） */
export function saveNutritionInfo(data: {
  productId: number
  energy?: number
  protein?: number
  fat?: number
  carbohydrate?: number
  calcium?: number
  sodium?: number
  remark?: string
}) {
  return post('/nutrition/info', data)
}

/** 营养摄入记录分页 */
export function getNutritionIntakeList(params: {
  pageNum: number
  pageSize: number
  studentId?: number
  startDate?: string
  endDate?: string
}) {
  return get('/nutrition/intake/list', params)
}

/** 营养摄入按日汇总 */
export function getNutritionIntakeSummary(params: {
  studentId: number
  startDate?: string
  endDate?: string
}) {
  return get('/nutrition/intake/summary', params)
}
