/**
 * 购物车：本地存储持久化的奶品清单（按奶品合并数量）
 * 结构：[{ productId, name, spec, price, quantity }]
 * 价格仅用于前端展示合计，实际金额以后端订单计算为准
 */
const CART_KEY = 'cartItems'
const MAX_QUANTITY = 99

function getItems() {
  return wx.getStorageSync(CART_KEY) || []
}

function save(items) {
  wx.setStorageSync(CART_KEY, items)
}

/** 总盒数（用于角标） */
function getCount() {
  return getItems().reduce((sum, x) => sum + x.quantity, 0)
}

/** 合计金额（前端展示用）：按分（整数）累加再转回元，避免浮点误差 */
function getTotal() {
  const cents = getItems().reduce(
    (sum, x) => sum + Math.round(Number(x.price) * 100) * x.quantity, 0)
  return cents / 100
}

/**
 * 加入购物车（同奶品自动合并数量）
 * @param {Object} product 奶品 { id, productName, spec, price }
 * @param {number} quantity 本次加购数量，默认 1
 * @param {number} [stock] 可选，奶品库存，用于限制上限
 */
function add(product, quantity, stock) {
  if (!product || !product.id) return
  const items = getItems()
  let item = items.find((x) => x.productId === product.id)
  if (item) {
    item.quantity += quantity || 1
  } else {
    item = {
      productId: product.id,
      name: product.productName,
      spec: product.spec,
      price: product.price,
      quantity: quantity || 1
    }
    items.push(item)
  }
  capQuantity(item, stock)
  save(items)
}

/** 修改指定奶品数量，数量减到 0 时移除 */
function setQuantity(productId, quantity, stock) {
  const items = getItems()
  const item = items.find((x) => x.productId === productId)
  if (!item) return
  if (quantity <= 0) {
    save(items.filter((x) => x.productId !== productId))
    return
  }
  item.quantity = quantity
  capQuantity(item, stock)
  save(items)
}

function remove(productId) {
  save(getItems().filter((x) => x.productId !== productId))
}

function clear() {
  wx.removeStorageSync(CART_KEY)
}

/** 数量上限：99 与库存（若提供）取较小值 */
function capQuantity(item, stock) {
  let max = MAX_QUANTITY
  if (stock != null && stock >= 0) {
    max = Math.min(max, stock)
  }
  if (item.quantity > max) item.quantity = max
  if (item.quantity < 1) item.quantity = 1
}

module.exports = {
  getItems,
  getCount,
  getTotal,
  add,
  setQuantity,
  remove,
  clear
}
