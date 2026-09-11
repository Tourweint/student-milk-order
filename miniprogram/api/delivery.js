/**
 * 配送记录接口
 */
const { get } = require('../utils/request')

/** 配送记录分页：{ pageNum, pageSize, deliveryDate, signStatus }（家长自动限定自己孩子） */
function getRecordList(params) {
  return get('/delivery/record/list', params)
}

module.exports = {
  getRecordList
}
