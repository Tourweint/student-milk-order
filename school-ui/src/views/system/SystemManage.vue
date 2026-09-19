<template>
  <div class="system-manage">
    <el-tabs v-model="activeTab">
      <!-- 角色管理 -->
      <el-tab-pane label="角色管理" name="role">
        <el-table :data="roleList" stripe>
          <el-table-column prop="roleCode" label="角色编码" width="140" />
          <el-table-column prop="roleName" label="角色名称" width="140" />
          <el-table-column prop="description" label="角色描述" min-width="250" />
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? '正常' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" class="tip"
          title="系统内置四种角色：管理员（全局管理）、班主任（班级数据权限）、家长（小程序端订购）、配送站（执行每日配送）。角色由系统初始化，不支持在线增删。" />
      </el-tab-pane>

      <!-- 状态机规则 -->
      <el-tab-pane label="状态机规则" name="rule">
        <div class="toolbar">
          <el-select v-model="ruleScene" placeholder="全部场景" clearable class="filter-item" @change="fetchRules">
            <el-option label="订单" value="ORDER" />
            <el-option label="配送任务" value="DELIVERY_TASK" />
          </el-select>
          <el-button type="primary" @click="fetchRules">查询</el-button>
        </div>

        <el-table v-loading="ruleLoading" :data="ruleList" stripe>
          <el-table-column label="场景" width="110">
            <template #default="{ row }">{{ sceneText(row.scene) }}</template>
          </el-table-column>
          <el-table-column prop="action" label="动作" width="150" />
          <el-table-column label="来源状态" width="120">
            <template #default="{ row }">{{ statusText(row.scene, row.fromStatus) }}</template>
          </el-table-column>
          <el-table-column label="是否允许" width="110">
            <template #default="{ row }">
              <el-switch :model-value="row.allowed === 1" @change="toggleRule(row, $event)" />
            </template>
          </el-table-column>
          <el-table-column prop="description" label="规则说明" min-width="300" show-overflow-tooltip />
        </el-table>
        <el-alert type="info" :closable="false" class="tip"
          title="状态迁移规则存于数据库，开关修改即刻生效（白名单语义：未列出的迁移一律禁止）。例：关闭「订单-PAY-待支付」后，所有支付落账将被拒绝，重新打开后自动恢复。" />
      </el-tab-pane>

      <!-- 过程迁移台账 -->
      <el-tab-pane label="过程迁移台账" name="transition">
        <div class="toolbar">
          <el-select v-model="transQuery.scene" placeholder="全部场景" clearable class="filter-item" @change="fetchTransitions">
            <el-option label="订单" value="ORDER" />
            <el-option label="配送任务" value="DELIVERY_TASK" />
            <el-option label="配送记录" value="DELIVERY_RECORD" />
          </el-select>
          <el-select v-model="transQuery.action" placeholder="全部动作" clearable class="filter-item" @change="fetchTransitions">
            <el-option v-for="(name, code) in actionNames" :key="code" :label="`${name}（${code}）`" :value="code" />
          </el-select>
          <el-select v-model="transQuery.result" placeholder="全部结果" clearable class="filter-item" @change="fetchTransitions">
            <el-option label="已生效" :value="1" />
            <el-option label="CAS 冲突未生效" :value="0" />
          </el-select>
          <el-input v-model="transQuery.bizNo" placeholder="业务单号（订单号/任务号）" clearable
            class="filter-item" @keyup.enter="fetchTransitions" />
          <el-date-picker v-model="transDateRange" type="datetimerange" range-separator="至"
            start-placeholder="开始时间" end-placeholder="结束时间" class="filter-item date-range" />
          <el-button type="primary" @click="fetchTransitions">查询</el-button>
          <el-button @click="handleTransitionReset">重置</el-button>
        </div>

        <el-table v-loading="transLoading" :data="transList" stripe>
          <el-table-column prop="createTime" label="时间" width="170" />
          <el-table-column label="场景" width="110">
            <template #default="{ row }">{{ sceneText(row.scene) }}</template>
          </el-table-column>
          <el-table-column label="动作" width="170">
            <template #default="{ row }">{{ actionText(row.action) }}</template>
          </el-table-column>
          <el-table-column label="状态迁移" width="190">
            <template #default="{ row }">
              {{ statusText(row.scene, row.fromStatus) }} → {{ statusText(row.scene, row.toStatus) }}
            </template>
          </el-table-column>
          <el-table-column prop="bizNo" label="业务单号" min-width="170" show-overflow-tooltip />
          <el-table-column label="结果" width="150">
            <template #default="{ row }">
              <el-tag :type="row.result === 1 ? 'success' : 'warning'" size="small">
                {{ row.result === 1 ? '已生效' : 'CAS 冲突未生效' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operatorName" label="操作人" width="130" />
          <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
        </el-table>

        <div class="pagination">
          <el-pagination
            v-model:current-page="transQuery.pageNum"
            v-model:page-size="transQuery.pageSize"
            :total="transTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="fetchTransitions"
            @current-change="fetchTransitions"
          />
        </div>

        <el-alert type="info" :closable="false" class="tip"
          title="每次状态迁移都由过程层写入一行台账（规则校验 + CAS 条件更新 + 留痕）。「已生效」表示本次迁移提交成功；「CAS 冲突未生效」表示并发下已被其他执行者抢先。注意：在「失败即中止事务」的严格路径中，冲突行会随事务回滚，只有事务继续的宽松路径（定时兜底、批量跳过）才会留下冲突记录。" />
      </el-tab-pane>

      <!-- 系统参数 -->
      <el-tab-pane label="系统参数" name="config">
        <el-table v-loading="configLoading" :data="configList" stripe>
          <el-table-column prop="configKey" label="参数键" min-width="220" />
          <el-table-column label="参数值" width="220">
            <template #default="{ row }">
              <el-input v-model="row.configValue" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="saveConfig(row)">保存</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="320" show-overflow-tooltip />
        </el-table>
        <el-alert type="info" :closable="false" class="tip"
          title="系统参数修改后立即生效（业务侧缓存最长 60 秒兜底刷新）。例：order.pay.timeout.minutes 控制待支付订单超时自动取消阈值；order.pay.reconcile.enabled 控制查单对账任务开关。" />
      </el-tab-pane>

      <!-- 操作日志 -->
      <el-tab-pane label="操作日志" name="log">
        <div class="toolbar">
          <el-input v-model="query.username" placeholder="用户名" clearable class="filter-item" @keyup.enter="fetchLogs" />
          <el-select v-model="query.status" placeholder="操作状态" clearable class="filter-item">
            <el-option label="成功" :value="1" />
            <el-option label="失败" :value="0" />
          </el-select>
          <el-date-picker v-model="dateRange" type="datetimerange" range-separator="至"
            start-placeholder="开始时间" end-placeholder="结束时间" class="filter-item date-range" />
          <el-button type="primary" @click="fetchLogs">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>

        <el-table v-loading="loading" :data="logList" stripe>
          <el-table-column prop="username" label="操作用户" width="120" />
          <el-table-column prop="operation" label="操作" width="160" />
          <el-table-column prop="requestUrl" label="请求URL" min-width="220" show-overflow-tooltip />
          <el-table-column prop="requestMethod" label="方法" width="80">
            <template #default="{ row }">
              <el-tag :type="methodTag(row.requestMethod)" size="small">{{ row.requestMethod }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="140" />
          <el-table-column prop="costTime" label="耗时(ms)" width="100" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                {{ row.status === 1 ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="操作时间" width="170" />
          <el-table-column label="详情" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="showDetail(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination">
          <el-pagination
            v-model:current-page="query.pageNum"
            v-model:page-size="query.pageSize"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="fetchLogs"
            @current-change="fetchLogs"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 日志详情抽屉 -->
    <el-drawer v-model="detailVisible" title="操作日志详情" size="480px">
      <el-descriptions :column="1" border v-if="currentLog">
        <el-descriptions-item label="操作用户">{{ currentLog.username }}</el-descriptions-item>
        <el-descriptions-item label="操作">{{ currentLog.operation }}</el-descriptions-item>
        <el-descriptions-item label="请求方法">{{ currentLog.method }}</el-descriptions-item>
        <el-descriptions-item label="请求URL">{{ currentLog.requestUrl }}</el-descriptions-item>
        <el-descriptions-item label="HTTP方法">{{ currentLog.requestMethod }}</el-descriptions-item>
        <el-descriptions-item label="IP地址">{{ currentLog.ip }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ currentLog.costTime }} ms</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="currentLog.status === 1 ? 'success' : 'danger'" size="small">
            {{ currentLog.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="操作时间">{{ currentLog.createTime }}</el-descriptions-item>
        <el-descriptions-item label="请求参数">
          <pre class="params-box">{{ currentLog.requestParams || '—' }}</pre>
        </el-descriptions-item>
        <el-descriptions-item v-if="currentLog.errorMsg" label="异常信息">
          <pre class="error-box">{{ currentLog.errorMsg }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getRoleList, getOperationLogList, getTransitionLogList,
  getStateRuleList, updateStateRule, getSysConfigList, updateSysConfig
} from '@/api/system'

const activeTab = ref('role')
const roleList = ref<any[]>([])
const logList = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const dateRange = ref<any>(null)
const detailVisible = ref(false)
const currentLog = ref<any>(null)

const query = reactive({
  pageNum: 1, pageSize: 10,
  username: '', status: undefined as number | undefined
})

// ==================== 状态机规则 ====================
const ruleScene = ref('')
const ruleList = ref<any[]>([])
const ruleLoading = ref(false)

const sceneNames: Record<string, string> = {
  ORDER: '订单',
  DELIVERY_TASK: '配送任务',
  DELIVERY_RECORD: '配送记录'
}
const statusNames: Record<string, Record<number, string>> = {
  ORDER: { 1: '待支付', 2: '已支付', 3: '配送中', 4: '已完成', 5: '已退订' },
  DELIVERY_TASK: { 1: '待配送', 2: '配送中', 3: '已完成', 4: '已取消' },
  DELIVERY_RECORD: { 1: '已签收', 2: '未签收', 3: '拒收' }
}
/** 迁移动作编码 → 中文（与后端 StateTransitions 常量一一对应） */
const actionNames: Record<string, string> = {
  PAY: '支付落账',
  CANCEL: '取消/退订',
  DELIVER: '订单进入配送中',
  AUTO_COMPLETE: '任务全终态自动完成',
  COMPLETE: '手动完成订单',
  DISPATCH: '任务送出',
  TASK_CANCEL: '任务取消',
  SIGN: '签收',
  REJECT: '拒收',
  STOCKOUT_CANCEL: '缺货取消'
}

function sceneText(scene: string) {
  return sceneNames[scene] || scene
}

function actionText(action: string) {
  return actionNames[action] ? `${actionNames[action]}（${action}）` : action
}

function statusText(scene: string, status: number) {
  return statusNames[scene]?.[status] ?? String(status)
}

async function fetchRules() {
  ruleLoading.value = true
  try {
    const res: any = await getStateRuleList(ruleScene.value || undefined)
    ruleList.value = res.data || []
  } finally {
    ruleLoading.value = false
  }
}

async function toggleRule(row: any, allowed: any) {
  await updateStateRule({ id: row.id, allowed: allowed ? 1 : 0 })
  row.allowed = allowed ? 1 : 0
  ElMessage.success('规则已更新，即刻生效')
}

// ==================== 过程迁移台账 ====================
const transQuery = reactive({
  pageNum: 1, pageSize: 10,
  scene: '', action: '', bizNo: '',
  result: undefined as number | undefined
})
const transDateRange = ref<any>(null)
const transList = ref<any[]>([])
const transTotal = ref(0)
const transLoading = ref(false)

async function fetchTransitions() {
  transLoading.value = true
  try {
    const params: any = { ...transQuery }
    if (transDateRange.value && transDateRange.value.length === 2) {
      params.startTime = transDateRange.value[0]
      params.endTime = transDateRange.value[1]
    }
    const res: any = await getTransitionLogList(params)
    transList.value = res.data.list
    transTotal.value = Number(res.data.total)
  } finally {
    transLoading.value = false
  }
}

function handleTransitionReset() {
  transQuery.scene = ''
  transQuery.action = ''
  transQuery.bizNo = ''
  transQuery.result = undefined
  transDateRange.value = null
  transQuery.pageNum = 1
  fetchTransitions()
}

// ==================== 系统参数 ====================
const configList = ref<any[]>([])
const configLoading = ref(false)

async function fetchConfigs() {
  configLoading.value = true
  try {
    const res: any = await getSysConfigList()
    configList.value = res.data || []
  } finally {
    configLoading.value = false
  }
}

async function saveConfig(row: any) {
  if (!row.configValue && row.configValue !== '0') {
    ElMessage.warning('参数值不能为空')
    return
  }
  await updateSysConfig({ id: row.id, configValue: String(row.configValue) })
  ElMessage.success('参数已保存，即刻生效')
}

function methodTag(method: string) {
  if (method === 'GET') return 'info'
  if (method === 'POST') return 'success'
  if (method === 'PUT') return 'warning'
  if (method === 'DELETE') return 'danger'
  return ''
}

async function fetchRoles() {
  const res: any = await getRoleList()
  roleList.value = res.data || []
}

async function fetchLogs() {
  loading.value = true
  try {
    const params: any = { ...query }
    if (dateRange.value && dateRange.value.length === 2) {
      params.startTime = dateRange.value[0]
      params.endTime = dateRange.value[1]
    }
    const res: any = await getOperationLogList(params)
    logList.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

function handleReset() {
  query.username = ''
  query.status = undefined
  dateRange.value = null
  query.pageNum = 1
  fetchLogs()
}

function showDetail(row: any) {
  currentLog.value = row
  detailVisible.value = true
}

onMounted(() => {
  fetchRoles()
  fetchLogs()
  fetchRules()
  fetchConfigs()
  fetchTransitions()
})
</script>

<style scoped lang="scss">
.system-manage { padding: 20px; }
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
  .filter-item { width: 180px; }
  .date-range { width: 360px; }
}
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
.tip { margin-top: 16px; }
.params-box {
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  max-height: 200px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.error-box {
  background: #fef0f0;
  color: #f56c6c;
  padding: 8px;
  border-radius: 4px;
  max-height: 200px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  margin: 0;
}
</style>
