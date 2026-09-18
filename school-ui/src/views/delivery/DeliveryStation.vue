<template>
  <div class="delivery-station">
    <!-- 日期 + 今日已送出 -->
    <div class="toolbar">
      <span class="toolbar-label">配送日期</span>
      <el-date-picker
        v-model="queryDate"
        type="date"
        value-format="YYYY-MM-DD"
        :clearable="false"
        class="date-item"
        @change="refresh"
      />
      <el-button :icon="Refresh" @click="refresh">刷新</el-button>
      <div class="spacer" />
      <el-tag v-if="lastDispatchInfo" type="info" size="large" class="dispatch-tag">{{ lastDispatchInfo }}</el-tag>
      <el-button type="success" :loading="dispatching" @click="handleDispatchAll">今日已送出</el-button>
    </div>

    <!-- 未签收提醒：已送出但未签收的任务（配送站与老师线下接触多，可口头提醒） -->
    <div v-if="pendingDispatchTotal > 0" class="pending-bar">
      <el-icon class="pending-icon"><Bell /></el-icon>
      <span class="pending-text">
        今日已送出 <b>{{ pendingDispatchTotal }}</b> 条尚未签收，请提醒班主任及时签收；次日凌晨未签收将自动签收兜底。
      </span>
    </div>

    <!-- 按班级汇总概览 -->
    <el-table v-loading="summaryLoading" :data="summaryList" stripe class="summary-table">
      <el-table-column prop="className" label="班级" min-width="140">
        <template #default="{ row }">{{ row.className || '未知班级' }}</template>
      </el-table-column>
      <el-table-column prop="total" label="应送" width="90" align="center" />
      <el-table-column label="待配送" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.pending > 0" type="warning" size="small">{{ row.pending }}</el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column label="已送出" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.dispatching > 0" type="primary" size="small">{{ row.dispatching }}</el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column label="已签收" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.completed > 0" type="success" size="small">{{ row.completed }}</el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column prop="cancelled" label="已取消" width="90" align="center" />
    </el-table>

    <el-tabs v-model="activeTab" class="station-tabs">
      <!-- 配送任务明细 -->
      <el-tab-pane label="配送任务" name="task">
        <div class="toolbar sub-toolbar">
          <el-date-picker
            v-model="taskRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="date-item"
          />
          <el-input v-model="taskQuery.orderNo" placeholder="订单号" clearable class="order-item" @keyup.enter="fetchTasks" />
          <el-select v-model="taskQuery.status" placeholder="全部状态" clearable class="status-item">
            <el-option v-for="s in taskStatusOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
          <el-button type="primary" @click="fetchTasks">查询</el-button>
          <el-button @click="resetTaskQuery">重置</el-button>
          <span class="tip">支持日期区间与订单号筛选，可查看跨月订单的整期配送任务</span>
        </div>

        <el-table v-loading="taskLoading" :data="taskList" stripe>
          <el-table-column prop="taskNo" label="任务编号" min-width="170" />
          <el-table-column prop="deliveryDate" label="配送日期" width="110" />
          <el-table-column prop="className" label="班级" width="110" />
          <el-table-column prop="studentName" label="学生" width="90" />
          <el-table-column prop="productName" label="奶品" min-width="110" />
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="taskStatusTag(row.status)" size="small">{{ taskStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="派送人" width="100">
            <template #default="{ row }">{{ row.dispatchBy || '—' }}</template>
          </el-table-column>
          <el-table-column label="派送时间" min-width="160">
            <template #default="{ row }">{{ row.dispatchTime || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 1" link type="primary" @click="handleStartOne(row)">开始配送</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination">
          <el-pagination
            v-model:current-page="taskQuery.pageNum"
            v-model:page-size="taskQuery.pageSize"
            :total="taskTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="fetchTasks"
            @current-change="fetchTasks"
          />
        </div>
      </el-tab-pane>

      <!-- 签收情况（只读，签收由班主任执行） -->
      <el-tab-pane label="签收情况" name="record">
        <el-table v-loading="recordLoading" :data="recordList" stripe>
          <el-table-column prop="deliveryDate" label="配送日期" width="110" />
          <el-table-column prop="className" label="班级" width="110" />
          <el-table-column prop="studentName" label="学生" width="90" />
          <el-table-column prop="productName" label="奶品" min-width="110" />
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column label="签收状态" width="100">
            <template #default="{ row }">
              <el-tag :type="signTag(row.signStatus)" size="small">{{ signText(row.signStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="signTime" label="签收时间" min-width="160">
            <template #default="{ row }">{{ row.signTime || '—' }}</template>
          </el-table-column>
          <el-table-column prop="signPerson" label="签收人" width="100">
            <template #default="{ row }">{{ row.signPerson || '—' }}</template>
          </el-table-column>
        </el-table>
        <div class="pagination">
          <el-pagination
            v-model:current-page="recordQuery.pageNum"
            v-model:page-size="recordQuery.pageSize"
            :total="recordTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="fetchRecords"
            @current-change="fetchRecords"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDailyTaskSummary, getDeliveryTaskList, getDeliveryRecordList,
  batchStartDeliveryTasks, startDeliveryTask
} from '@/api/delivery'

/** 某配送日期按班级汇总（后端 /delivery/task/summary） */
interface DailySummary {
  classId: number
  className: string | null
  total: number
  pending: number
  dispatching: number
  completed: number
  cancelled: number
}

/** 配送任务行（后端 DeliveryTaskVO） */
interface DeliveryTaskRow {
  id: number
  taskNo: string
  deliveryDate: string
  className: string | null
  studentName: string | null
  productName: string | null
  quantity: number
  status: number
  dispatchBy: string | null
  dispatchTime: string | null
}

/** 配送记录行（后端 DeliveryRecordVO，面板只读） */
interface DeliveryRecordRow {
  id: number
  deliveryDate: string
  className: string | null
  studentName: string | null
  productName: string | null
  quantity: number
  signStatus: number
  signTime: string | null
  signPerson: string | null
}

const queryDate = ref(today())
const activeTab = ref('task')
const lastDispatchInfo = ref('')

const summaryLoading = ref(false)
const summaryList = ref<DailySummary[]>([])

/** 已送出（配送中）未签收任务总数，用于顶部提醒班主任 */
const pendingDispatchTotal = computed(() =>
  summaryList.value.reduce((sum, row) => sum + (row.dispatching || 0), 0)
)

const taskLoading = ref(false)
const taskList = ref<DeliveryTaskRow[]>([])
const taskTotal = ref(0)
const taskRange = ref<[string, string] | null>(null)
const taskQuery = reactive({
  pageNum: 1,
  pageSize: 10,
  orderNo: '',
  status: undefined as number | undefined
})

const recordLoading = ref(false)
const recordList = ref<DeliveryRecordRow[]>([])
const recordTotal = ref(0)
const recordQuery = reactive({ pageNum: 1, pageSize: 10 })

const dispatching = ref(false)

const taskStatusOptions = [
  { value: 1, label: '待配送' },
  { value: 2, label: '配送中' },
  { value: 3, label: '已完成' },
  { value: 4, label: '已取消' }
]
const taskStatusText = (s: number) => taskStatusOptions.find((x) => x.value === s)?.label ?? '未知'
const taskStatusTag = (s: number): any => ({ 1: 'warning', 2: 'primary', 3: 'success', 4: 'danger' }[s] ?? 'info')

const signOptions = [
  { value: 1, label: '已签收' },
  { value: 2, label: '未签收' },
  { value: 3, label: '拒收' }
]
const signText = (s: number) => signOptions.find((x) => x.value === s)?.label ?? '未知'
const signTag = (s: number): any => ({ 1: 'success', 2: 'warning', 3: 'danger' }[s] ?? 'info')

function today(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

async function fetchSummary() {
  summaryLoading.value = true
  try {
    const res: any = await getDailyTaskSummary(queryDate.value)
    summaryList.value = res.data || []
  } finally {
    summaryLoading.value = false
  }
}

async function fetchTasks() {
  taskLoading.value = true
  try {
    const res: any = await getDeliveryTaskList({
      ...taskQuery,
      orderNo: taskQuery.orderNo || undefined,
      deliveryDate: taskRange.value?.[0],
      dateEnd: taskRange.value?.[1]
    })
    taskList.value = res.data.list
    taskTotal.value = Number(res.data.total)
  } finally {
    taskLoading.value = false
  }
}

async function fetchRecords() {
  recordLoading.value = true
  try {
    const res: any = await getDeliveryRecordList({ ...recordQuery, deliveryDate: queryDate.value })
    recordList.value = res.data.list
    recordTotal.value = Number(res.data.total)
  } finally {
    recordLoading.value = false
  }
}

function resetTaskQuery() {
  taskRange.value = null
  taskQuery.orderNo = ''
  taskQuery.status = undefined
  taskQuery.pageNum = 1
  fetchTasks()
}

function refresh() {
  fetchSummary()
  fetchRecords()
  fetchTasks()
}

/** 今日已送出：批量开始当日配送，之后相关订单不可退订 */
async function handleDispatchAll() {
  try {
    await ElMessageBox.confirm(
      `确定将 ${queryDate.value} 全部待配送任务标记为已送出吗？开始配送后相关订单将不可退订，操作人与时间将被记录。`,
      '今日已送出确认',
      { confirmButtonText: '确定送出', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  dispatching.value = true
  try {
    const res: any = await batchStartDeliveryTasks({ deliveryDate: queryDate.value })
    const count = Number(res?.data ?? 0)
    if (count > 0) {
      lastDispatchInfo.value = `${queryDate.value} 已送出 ${count} 项`
      ElMessage.success(`已送出 ${count} 项配送任务`)
    } else {
      ElMessage.info('该日期没有待配送的任务')
    }
    refresh()
  } finally {
    dispatching.value = false
  }
}

async function handleStartOne(row: DeliveryTaskRow) {
  await startDeliveryTask(row.id)
  ElMessage.success('已开始配送')
  refresh()
}

onMounted(refresh)
</script>

<style scoped lang="scss">
.delivery-station {
  padding: 20px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 16px;
  .toolbar-label { color: var(--el-text-color-regular); }
  .date-item { width: 150px; }
  .order-item { width: 200px; }
  .status-item { width: 130px; }
  .spacer { flex: 1; }
  .dispatch-tag { height: auto; padding: 4px 10px; }
}
.sub-toolbar {
  .date-item { width: 260px; }
  .tip { color: var(--el-text-color-secondary); font-size: 12px; }
}
.summary-table { margin-bottom: 8px; }
.pending-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 16px;
  background: #fdf3e3;
  border: 1px solid #f0d9b0;
  border-radius: 6px;
  .pending-icon { color: #e6a23c; font-size: 18px; }
  .pending-text { flex: 1; min-width: 200px; color: #7a5a20; font-size: 14px; }
  b { color: #c77700; }
}
.station-tabs {
  margin-top: 8px;
  :deep(.el-tab-pane) { padding-top: 4px; }
}
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
