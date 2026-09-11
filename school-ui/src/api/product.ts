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

// ==================== 库存与流水 ====================

/** 库存分页列表 */
export function getInventoryList(params: { pageNum: number; pageSize: number; keyword?: string }) {
  return get('/product/inventory/list', params)
}

/** 库存预警列表 */
export function getInventoryWarning() {
  return get('/product/inventory/warning')
}

/** 库存变动（入库/出库/盘盈/盘亏） */
export function changeInventory(data: { productId: number; changeType: number; changeQuantity: number; remark?: string }) {
  return post('/product/inventory/change', data)
}

/** 设置预警阈值/库位 */
export function updateInventoryThreshold(data: { id: number; warningThreshold?: number; warehouseLocation?: string }) {
  return put('/product/inventory/threshold', data)
}

/** 库存流水分页 */
export function getInventoryRecords(params: { pageNum: number; pageSize: number; productId?: number; changeType?: number }) {
  return get('/product/inventory/record/list', params)
}
