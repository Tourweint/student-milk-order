import { get, put } from '@/utils/request'

/** 角色列表 */
export function getRoleList() {
  return get('/system/role/list')
}

// ==================== 状态机规则（管理端在线配置） ====================

/** 状态迁移规则列表（scene 可选：ORDER/DELIVERY_TASK） */
export function getStateRuleList(scene?: string) {
  return get('/system/state-rule/list', { scene })
}

/** 修改状态迁移规则（allowed：1-允许，0-禁止），在线生效 */
export function updateStateRule(data: { id: number; allowed?: number; description?: string }) {
  return put('/system/state-rule', data)
}

// ==================== 过程迁移台账（只读） ====================

/**
 * 过程迁移台账分页：每次状态迁移由过程层写入一行（成功与 CAS 冲突都记录），
 * 用于过程回放、问题回溯与父子状态对账取证。
 */
export function getTransitionLogList(params: {
  pageNum: number
  pageSize: number
  scene?: string
  action?: string
  entityType?: string
  bizNo?: string
  result?: number
  startTime?: string
  endTime?: string
}) {
  return get('/system/transition-log/list', params)
}

// ==================== 系统参数（管理端在线配置） ====================

/** 系统参数列表 */
export function getSysConfigList() {
  return get('/system/config/list')
}

/** 修改系统参数值，在线生效 */
export function updateSysConfig(data: { id: number; configValue: string }) {
  return put('/system/config', data)
}

/** 操作日志分页 */
export function getOperationLogList(params: {
  pageNum: number
  pageSize: number
  username?: string
  status?: number
  startTime?: string
  endTime?: string
}) {
  return get('/system/log/list', params)
}
