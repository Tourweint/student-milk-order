/**
 * 奶品与套餐接口
 */
const { get } = require('../utils/request')

/** 奶品分页列表：{ pageNum, pageSize, categoryId, keyword } */
function getProductList(params) {
  return get('/product/list', params)
}

/** 奶品详情 */
function getProductDetail(id) {
  return get('/product/' + id)
}

/** 套餐列表 */
function getPackageList() {
  return get('/product/package/list')
}

/** 套餐详情 */
function getPackageDetail(id) {
  return get('/product/package/' + id)
}

module.exports = {
  getProductList,
  getProductDetail,
  getPackageList,
  getPackageDetail
}
