import { get, post } from '@/utils/request'

// ==================== 仓库余量台账（供给侧） ====================
// 一条进出账：IN 到货 / OUT 送出 / IN_BACK 退回 / ADJ 修正 / INIT 期初。
// 出库与退回由「开始配送」「拒收」在同一事务内自动入账，没有独立接口。

/** 到货登记请求 */
export interface WarehouseReceiptPayload {
  /** 到货日期（不得晚于今天：到货是既成事实） */
  bizDate: string
  productId: number
  /** 实到盒数（>0） */
  quantity: number
  /** 可选：批次号（召回反查用） */
  batchNo?: string
  /** 可选：凭证号；不填按 MAIN，同日同品种第二车请填各自送货单号 */
  receiptNo?: string
  /** 可选：备注 */
  note?: string
}

/** 到货登记结果（含短交预警） */
export interface WarehouseReceiptResult {
  id: number
  bizDate: string
  productId: number
  productName: string | null
  quantity: number
  receiptNo: string | null
  batchNo: string | null
  /** 登记后的仓库余量 W */
  balance: number
  /** 当日应到（池剩余 + 待送出任务） */
  expectedQuantity: number
  /** 缺口（应到 − 实到），无缺口为 null */
  shortfall: number | null
  /** 短交预警消息（不阻断登记），无缺口为 null */
  warning: string | null
}

/** 某品种当前余量 */
export interface WarehouseBalance {
  productId: number
  productName: string | null
  balance: number
}

/** 台账行（后端实体字段） */
export interface WarehouseLedgerRow {
  id: number
  /** IN/OUT/IN_BACK/ADJ/INIT */
  bizType: string
  bizDate: string
  productId: number
  /** IN/OUT/IN_BACK/INIT 恒为正；ADJ 带符号（正=增加余量，负=减少余量） */
  quantity: number
  refType: string | null
  refId: number | null
  receiptNo: string | null
  batchNo: string | null
  reason: string | null
  operator: string | null
  createTime: string
}

/**
 * 登记到货（配送站 / 管理员）。
 * 响应带**短交预警**：应到 > 实到时给出消息但**不阻断登记**（企业可能分批到货）。
 */
export function receiptWarehouse(data: WarehouseReceiptPayload) {
  return post('/warehouse/receipt', data)
}

/** 当前仓库余量（按品种；在售品种全列，未登记到货的为 0） */
export function getWarehouseBalance() {
  return get('/warehouse/balance')
}

/** 台账查询（仅管理员）：类型 / 品种 / 日期区间，分页 */
export function getWarehouseLedger(params: {
  pageNum: number
  pageSize: number
  bizType?: string
  productId?: number
  startDate?: string
  endDate?: string
}) {
  return get('/warehouse/ledger', params)
}

/**
 * 修正（仅管理员，必填原因）。
 * 台账只增不改：记错走反向修正冲销（两条留痕），不提供修改/删除接口。
 */
export function adjustWarehouse(data: { productId: number; quantity: number; reason: string }) {
  return post('/warehouse/adjustment', data)
}
