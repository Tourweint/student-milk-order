import { get, post, put, del } from '@/utils/request'

// ==================== 品类 ====================

/** 品类列表 */
export function getCategoryList() {
  return get('/product/category/list')
}

/** 新增品类 */
export function saveCategory(data: any) {
  return post('/product/category', data)
}

/** 修改品类 */
export function updateCategory(data: any) {
  return put('/product/category', data)
}

/** 删除品类 */
export function deleteCategory(id: number) {
  return del(`/product/category/${id}`)
}

// ==================== 奶品 ====================

/** 奶品分页列表 */
export function getProductList(params: { pageNum: number; pageSize: number; categoryId?: number; keyword?: string }) {
  return get('/product/list', params)
}

/** 奶品详情 */
export function getProductById(id: number) {
  return get(`/product/${id}`)
}

/** 新增奶品 */
export function saveProduct(data: any) {
  return post('/product', data)
}

/** 修改奶品 */
export function updateProduct(data: any) {
  return put('/product', data)
}

/** 删除奶品 */
export function deleteProduct(id: number) {
  return del(`/product/${id}`)
}

// ==================== 套餐 ====================

/** 套餐列表 */
export function getPackageList() {
  return get('/product/package/list')
}

/** 套餐详情 */
export function getPackageById(id: number) {
  return get(`/product/package/${id}`)
}

/** 新增套餐 */
export function savePackage(data: any) {
  return post('/product/package', data)
}

/** 修改套餐 */
export function updatePackage(data: any) {
  return put('/product/package', data)
}

/** 删除套餐 */
export function deletePackage(id: number) {
  return del(`/product/package/${id}`)
}

// ==================== 每日机动配额（单日零散订购用） ====================

/** 配额列表（日期区间） */
export function getQuotaList(params: { startDate?: string; endDate?: string }) {
  return get('/product/quota/list', params)
}

/** 某日各品种剩余机动盒数 */
export function getQuotaRemainingList(date: string) {
  return get('/product/quota/remaining/list', { date })
}

/** 批量设置某日各品种配额 */
export function setQuotaBatch(data: { quotaDate: string; items: { productId: number; totalQuota: number }[]; remark?: string }) {
  return put('/product/quota/batch', data)
}
