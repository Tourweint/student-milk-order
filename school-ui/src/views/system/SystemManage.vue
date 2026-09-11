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
          title="系统内置三种角色：管理员（全局管理）、班主任（班级数据权限）、家长（小程序端订购）。角色由系统初始化，不支持在线增删。" />
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
import { getRoleList, getOperationLogList } from '@/api/system'

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
