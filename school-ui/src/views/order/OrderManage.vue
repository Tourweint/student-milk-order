<template>
  <div class="order-manage">
    <!-- 筛选栏 -->
    <div class="search-bar">
      <el-select v-model="query.classId" placeholder="全部班级" clearable filterable class="filter-select" @change="handleSearch">
        <el-option v-for="c in classList" :key="c.id" :label="classLabel(c)" :value="c.id" />
      </el-select>
      <el-select v-model="query.status" placeholder="全部状态" clearable class="filter-select" @change="handleSearch">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-date-picker
        v-model="dateRange" type="daterange" range-separator="至"
        start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
        class="date-picker" @change="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="createVisible = true">创建订单</el-button>
    </div>

    <!-- 订单表格 -->
    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="orderNo" label="订单号" min-width="180" />
      <el-table-column prop="studentName" label="学生" width="100" />
      <el-table-column prop="className" label="班级" width="120" />
      <el-table-column prop="packageName" label="套餐" min-width="130">
        <template #default="{ row }">{{ row.packageName || '—' }}</template>
      </el-table-column>
      <el-table-column label="金额" width="100">
        <template #default="{ row }">¥{{ Number(row.payAmount).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="160" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 1" link type="success" @click="handlePay(row)">支付</el-button>
          <el-button v-if="row.status === 2" link type="warning" @click="handleDeliver(row)">开始配送</el-button>
          <el-button v-if="row.status === 3" link type="primary" @click="handleComplete(row)">完成</el-button>
          <el-button v-if="row.status === 1 || row.status === 2" link type="danger" @click="handleCancel(row)">退订</el-button>
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
        @size-change="fetchList"
        @current-change="fetchList"
      />
    </div>

    <!-- 创建订单对话框 -->
    <OrderCreateDialog v-model:visible="createVisible" @success="onCreated" />

    <!-- 订单详情抽屉 -->
    <OrderDetailDrawer v-model:visible="detailVisible" :order-id="currentOrderId" @status-changed="fetchList" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getOrderList, payOrder, cancelOrder, deliverOrder, completeOrder
} from '@/api/order'
import { getAllClass } from '@/api/clazz'
import OrderCreateDialog from './components/OrderCreateDialog.vue'
import OrderDetailDrawer from './components/OrderDetailDrawer.vue'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const classList = ref<any[]>([])
const dateRange = ref<[string, string] | null>(null)

const query = reactive({
  pageNum: 1, pageSize: 10,
  classId: undefined as number | undefined,
  status: undefined as number | undefined,
  startDate: undefined as string | undefined,
  endDate: undefined as string | undefined
})

const statusOptions = [
  { value: 1, label: '待支付' },
  { value: 2, label: '已支付' },
  { value: 3, label: '配送中' },
  { value: 4, label: '已完成' },
  { value: 5, label: '已退订' }
]
const statusText = (s: number) => statusOptions.find((x) => x.value === s)?.label ?? '未知'
const statusTag = (s: number): any => {
  const map: Record<number, string> = { 1: 'warning', 2: 'primary', 3: 'info', 4: 'success', 5: 'danger' }
  return map[s] ?? 'info'
}
const classLabel = (c: any) => c.gradeName ? `${c.gradeName} · ${c.className}` : c.className

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getOrderList(query)
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

async function fetchClasses() {
  const res: any = await getAllClass()
  classList.value = res.data
}

function handleSearch() {
  if (dateRange.value) {
    query.startDate = dateRange.value[0]
    query.endDate = dateRange.value[1]
  } else {
    query.startDate = undefined
    query.endDate = undefined
  }
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.classId = undefined
  query.status = undefined
  dateRange.value = null
  query.startDate = undefined
  query.endDate = undefined
  query.pageNum = 1
  fetchList()
}

// 操作
async function handlePay(row: any) {
  await ElMessageBox.confirm(`确定支付订单「${row.orderNo}」吗？`, '提示', {
    confirmButtonText: '确定支付', cancelButtonText: '取消', type: 'warning'
  })
  await payOrder(row.id)
  ElMessage.success('支付成功')
  fetchList()
}

async function handleDeliver(row: any) {
  await deliverOrder(row.id)
  ElMessage.success('已开始配送')
  fetchList()
}

async function handleComplete(row: any) {
  await completeOrder(row.id)
  ElMessage.success('订单已完成')
  fetchList()
}

async function handleCancel(row: any) {
  const { value: reason } = await ElMessageBox.prompt('请输入退订原因（可选）', '退订确认', {
    confirmButtonText: '确定退订', cancelButtonText: '取消', type: 'warning',
    inputPlaceholder: '可留空'
  }).catch(() => ({ value: undefined as any }))
  if (reason === undefined) return
  await cancelOrder(row.id, reason || undefined)
  ElMessage.success('已退订')
  fetchList()
}

// 详情
const detailVisible = ref(false)
const currentOrderId = ref<number | null>(null)
function openDetail(row: any) {
  currentOrderId.value = row.id
  detailVisible.value = true
}

// 创建
const createVisible = ref(false)
function onCreated() {
  createVisible.value = false
  fetchList()
}

onMounted(() => {
  fetchClasses()
  fetchList()
})
</script>

<style scoped lang="scss">
.order-manage {
  padding: 20px;
}
.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  .filter-select { width: 160px; }
  .date-picker { width: 280px; }
}
.toolbar { margin-bottom: 16px; }
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
