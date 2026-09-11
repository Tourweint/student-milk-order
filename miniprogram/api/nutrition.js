/**
 * 营养统计接口
 */
const { get } = require('../utils/request')

/** 营养摄入分页：{ pageNum, pageSize, startDate, endDate }（家长自动限定自己孩子） */
function getIntakeList(params) {
  return get('/nutrition/intake/list', params)
}

/** 营养摄入汇总：{ startDate, endDate } */
function getIntakeSummary(params) {
  return get('/nutrition/intake/summary', params)
}

/** 奶品营养成分 */
function getNutritionInfo(productId) {
  return get('/nutrition/info/' + productId)
}

module.exports = {
  getIntakeList,
  getIntakeSummary,
  getNutritionInfo
}
