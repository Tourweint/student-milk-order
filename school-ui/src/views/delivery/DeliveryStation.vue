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

    <!-- 未送达申报待跟进：这些任务被排除出自动签收候选集，必须人工处置 -->
    <div v-if="pendingReportTotal > 0" class="pending-bar report-bar">
      <el-icon class="pending-icon"><Warning /></el-icon>
      <span class="pending-text">
        {{ queryDate }} 有 <b>{{ pendingReportTotal }}</b> 条「未送达申报」待跟进：这些任务<b>不会</b>被次日自动签收兜底签掉，
        请核实后走签收 / 拒收 / 取消处置（申报只做标记，不改变任务状态）。
      </span>
      <el-button size="small" @click="switchToReportTab">去跟进</el-button>
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
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 1" link type="primary" @click="handleStartOne(row)">开始配送</el-button>
              <el-button v-if="row.status === 2" link type="warning" @click="reportUndeliveredTask(row)">申报未送达</el-button>
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

      <!-- 未送达申报：待跟进待办（申报后任务不会被自动签收兜底签掉） -->
      <el-tab-pane label="未送达申报" name="report">
        <div class="toolbar sub-toolbar">
          <el-select v-model="reportQuery.handleStatus" placeholder="全部（待跟进+已跟进）" clearable class="status-item" @change="fetchReports">
            <el-option label="待跟进" :value="0" />
            <el-option label="已跟进" :value="1" />
          </el-select>
          <el-checkbox v-model="reportAllDates" @change="fetchReports">不限配送日（默认只看当前日期）</el-checkbox>
          <el-button type="primary" @click="fetchReports">查询</el-button>
          <el-button @click="resetReportQuery">重置</el-button>
          <span class="tip">申报只做标记：任务仍停留在「配送中」，处置必须走签收 / 拒收 / 取消</span>
        </div>
        <el-table v-loading="reportLoading" :data="reportList" stripe>
          <el-table-column prop="deliveryDate" label="配送日期" width="110" />
          <el-table-column prop="className" label="班级" width="110" />
          <el-table-column prop="studentName" label="学生" width="90" />
          <el-table-column prop="productName" label="奶品" min-width="110" />
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column prop="taskNo" label="任务编号" min-width="170" />
          <el-table-column prop="reason" label="未送达原因" min-width="160" show-overflow-tooltip />
          <el-table-column label="申报人 / 时间" min-width="160">
            <template #default="{ row }">{{ row.reportBy || '—' }} / {{ row.reportTime || '—' }}</template>
          </el-table-column>
          <el-table-column label="任务状态" width="100">
            <template #default="{ row }">
              <el-tag :type="taskStatusTag(row.taskStatus)" size="small">{{ taskStatusText(row.taskStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="跟进" width="230" fixed="right">
            <template #default="{ row }">
              <template v-if="row.handleStatus === 0">
                <el-button link type="warning" @click="goHandleTask(row)">去处置任务</el-button>
                <el-button v-if="isAdmin" link type="primary" @click="handleReport(row)">标记已跟进</el-button>
              </template>
              <span v-else class="handled-text">
                {{ row.handleBy || '—' }} 已跟进{{ row.handleRemark ? '：' + row.handleRemark : '' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination">
          <el-pagination
            v-model:current-page="reportQuery.pageNum"
            v-model:page-size="reportQuery.pageSize"
            :total="reportTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="fetchReports"
            @current-change="fetchReports"
          />
        </div>
      </el-tab-pane>

      <!-- 仓库余量（供给侧）：收货点数登记 + 当前余量。余量是机动配额的上限，未登记则当天设不了配额 -->
      <el-tab-pane label="仓库余量" name="warehouse">
        <el-alert
          type="info"
          :closable="false"
          class="warehouse-tip"
          title="仓库余量是机动配额发行的上限（只有仓库余量才能卖）"
          description="每天收货点数后按品种登记到货；未登记则余量为 0、管理员当天设不了机动配额。同一品种当天第二车请填写各自的送货单号作为凭证号。"
        />
        <el-form :inline="true" class="sub-toolbar" :model="receiptForm">
          <el-form-item label="到货日期">
            <el-date-picker
              v-model="receiptForm.bizDate"
              type="date"
              value-format="YYYY-MM-DD"
              :clearable="false"
              class="date-item"
            />
          </el-form-item>
          <el-form-item label="奶品">
            <el-select v-model="receiptForm.productId" placeholder="选择奶品" filterable class="order-item">
              <el-option
                v-for="p in balanceList"
                :key="p.productId"
                :label="`${p.productName || '奶品' + p.productId}（余量 ${p.balance}）`"
                :value="p.productId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="到货盒数">
            <el-input-number v-model="receiptForm.quantity" :min="1" :step="1" controls-position="right" class="num-item" />
          </el-form-item>
          <el-form-item label="凭证号">
            <el-input v-model="receiptForm.receiptNo" placeholder="默认 MAIN；第二车填送货单号" class="order-item" />
          </el-form-item>
          <el-form-item label="批次号">
            <el-input v-model="receiptForm.batchNo" placeholder="可选（召回反查用）" class="order-item" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="receiptSubmitting" @click="submitReceipt">登记到货</el-button>
            <el-button @click="fetchBalance">刷新余量</el-button>
          </el-form-item>
        </el-form>

        <el-table v-loading="balanceLoading" :data="balanceList" stripe>
          <el-table-column prop="productId" label="奶品ID" width="90" />
          <el-table-column label="奶品" min-width="150">
            <template #default="{ row }">{{ row.productName || '—' }}</template>
          </el-table-column>
          <el-table-column label="当前余量（盒）" width="140" align="center">
            <template #default="{ row }">
              <el-tag :type="row.balance > 0 ? 'success' : 'danger'" size="small">{{ row.balance }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提示" min-width="220">
            <template #default="{ row }">
              <span v-if="row.balance <= 0" class="warehouse-warn">
                余量为 0：请先登记到货，否则当天无法设置该品种的机动配额
              </span>
              <span v-else class="warehouse-muted">送出即出库（签收不记账）；拒收退回自动增加余量</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Refresh, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDailyTaskSummary, getDeliveryTaskList, getDeliveryRecordList,
  batchStartDeliveryTasks, startDeliveryTask,
  reportUndelivered, getUndeliveredReportList, handleUndeliveredReport
} from '@/api/delivery'
import { getWarehouseBalance, receiptWarehouse } from '@/api/warehouse'
import { useUserStore } from '@/stores/user'

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

/** 未送达申报待办（只有 ADMIN 能标记已跟进；申报由 ADMIN/DELIVERY 发起） */
const userStore = useUserStore()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))
const reportLoading = ref(false)
const reportList = ref<any[]>([])
const reportTotal = ref(0)
const pendingReportTotal = ref(0)
const reportAllDates = ref(false)
const reportQuery = reactive({
  pageNum: 1,
  pageSize: 10,
  handleStatus: undefined as number | undefined
})

const dispatching = ref(false)

/** 仓库余量（供给侧）：到货登记 + 当前余量。余量是机动配额的上限，未登记则设不了配额 */
const balanceLoading = ref(false)
const balanceList = ref<{ productId: number; productName: string | null; balance: number }[]>([])
const receiptSubmitting = ref(false)
const receiptForm = reactive({
  bizDate: today(),
  productId: undefined as number | undefined,
  quantity: 1,
  receiptNo: '',
  batchNo: ''
})

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

async function fetchReports() {
  reportLoading.value = true
  try {
    const res: any = await getUndeliveredReportList({
      pageNum: reportQuery.pageNum,
      pageSize: reportQuery.pageSize,
      handleStatus: reportQuery.handleStatus,
      deliveryDate: reportAllDates.value ? undefined : queryDate.value
    })
    reportList.value = res.data.list
    reportTotal.value = Number(res.data.total)
  } finally {
    reportLoading.value = false
  }
}

/** 顶部提醒条：当前配送日的「待跟进」条数 */
async function fetchPendingReportTotal() {
  try {
    const res: any = await getUndeliveredReportList({
      pageNum: 1,
      pageSize: 1,
      deliveryDate: queryDate.value,
      handleStatus: 0
    })
    pendingReportTotal.value = Number(res.data.total)
  } catch {
    pendingReportTotal.value = 0
  }
}

function resetReportQuery() {
  reportQuery.handleStatus = undefined
  reportQuery.pageNum = 1
  reportAllDates.value = false
  fetchReports()
}

function switchToReportTab() {
  activeTab.value = 'report'
  fetchReports()
}

/** 从申报待办跳到配送任务页并按订单号定位，便于直接走签收/拒收/取消处置 */
function goHandleTask(row: any) {
  activeTab.value = 'task'
  taskRange.value = null
  taskQuery.orderNo = row.orderNo || ''
  taskQuery.status = undefined
  taskQuery.pageNum = 1
  fetchTasks()
}

/** 奶站申报「当日未送达」：任务被标记已送出但物理上没送到 */
async function reportUndeliveredTask(row: DeliveryTaskRow) {
  let reason: string
  try {
    const res = await ElMessageBox.prompt(
      `任务「${row.taskNo}」（${row.deliveryDate} ${row.studentName || ''} ${row.productName || ''}）已标记送出但实际未送达。` +
      '申报后该任务不会被自动签收兜底签掉，请填写具体原因：',
      '未送达申报',
      { confirmButtonText: '提交申报', cancelButtonText: '取消', inputPlaceholder: '如：车辆故障 / 道路中断 / 奶未备齐' }
    )
    reason = String(res.value || '').trim()
  } catch {
    return
  }
  if (!reason) {
    ElMessage.warning('请填写未送达原因')
    return
  }
  await reportUndelivered({ taskId: row.id, reason })
  ElMessage.success('已申报未送达，该任务不会被自动签收兜底')
  refresh()
}

/** 标记已跟进（仅 ADMIN）：只写跟进说明，任务状态不变 */
async function handleReport(row: any) {
  let remark: string
  try {
    const res = await ElMessageBox.prompt(
      `确认「${row.taskNo}」已人工处置完毕？请填写处置说明（如：已线下补送 / 已取消并回补）：`,
      '标记已跟进',
      { confirmButtonText: '确认', cancelButtonText: '取消', inputPlaceholder: '处置说明' }
    )
    remark = String(res.value || '').trim()
  } catch {
    return
  }
  await handleUndeliveredReport(row.id, remark)
  ElMessage.success('已标记跟进')
  fetchReports()
  fetchPendingReportTotal()
}

function refresh() {
  fetchSummary()
  fetchRecords()
  fetchTasks()
  fetchReports()
  fetchPendingReportTotal()
  fetchBalance()
}

/** 仓库余量（按品种） */
async function fetchBalance() {
  balanceLoading.value = true
  try {
    const res: any = await getWarehouseBalance()
    balanceList.value = res.data || []
  } finally {
    balanceLoading.value = false
  }
}

/**
 * 登记到货（收货点数）。
 * 短交预警只提示、不阻断——企业可能分批到货，第二车填各自送货单号即可。
 */
async function submitReceipt() {
  if (!receiptForm.productId) {
    ElMessage.warning('请选择奶品')
    return
  }
  if (!receiptForm.quantity || receiptForm.quantity < 1) {
    ElMessage.warning('请填写到货盒数')
    return
  }
  receiptSubmitting.value = true
  try {
    const res: any = await receiptWarehouse({
      bizDate: receiptForm.bizDate,
      productId: receiptForm.productId,
      quantity: receiptForm.quantity,
      receiptNo: receiptForm.receiptNo.trim() || undefined,
      batchNo: receiptForm.batchNo.trim() || undefined
    })
    const warning = res?.data?.warning
    if (warning) {
      ElMessageBox.alert(warning, '短交预警（登记已成功）', { confirmButtonText: '知道了', type: 'warning' })
    } else {
      ElMessage.success(`已登记到货，当前余量 ${res?.data?.balance ?? 0} 盒`)
    }
    receiptForm.quantity = 1
    receiptForm.receiptNo = ''
    receiptForm.batchNo = ''
    fetchBalance()
  } finally {
    receiptSubmitting.value = false
  }
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
  .num-item { width: 130px; }
  .tip { color: var(--el-text-color-secondary); font-size: 12px; }
}
/* 仓库余量：未登记到货的品种用红色提示（它直接决定当天能不能设配额） */
.warehouse-tip { margin-bottom: 12px; }
.warehouse-warn { color: var(--el-color-danger); font-size: 13px; }
.warehouse-muted { color: var(--el-text-color-secondary); font-size: 13px; }
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
/* 未送达申报提醒：比"待签收"更严重（兜底不会兜它），用红色区分 */
.report-bar {
  background: #fdeaea;
  border-color: #f3bcbc;
  .pending-icon { color: #f56c6c; }
  .pending-text { color: #a33; }
  b { color: #d32f2f; }
}
.handled-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
