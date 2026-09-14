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
            <el-option label="续订计划" value="SUBSCRIPTION_PLAN" />
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
  getRoleList, getOperationLogList,
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
  SUBSCRIPTION_PLAN: '续订计划'
}
const statusNames: Record<string, Record<number, string>> = {
  ORDER: { 1: '待支付', 2: '已支付', 3: '配送中', 4: '已完成', 5: '已退订' },
  DELIVERY_TASK: { 1: '待配送', 2: '配送中', 3: '已完成', 4: '已取消' },
  SUBSCRIPTION_PLAN: { 0: '已关闭', 1: '已开启', 2: '已暂停' }
}

function sceneText(scene: string) {
  return sceneNames[scene] || scene
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
