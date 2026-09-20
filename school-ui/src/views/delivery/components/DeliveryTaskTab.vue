<template>
  <div class="task-tab">
    <!-- 筛选 + 生成 -->
    <div class="toolbar">
      <el-date-picker v-model="queryDate" type="date" placeholder="配送日期" value-format="YYYY-MM-DD" class="filter-item" />
      <GradeClassFilter v-model="query.classId" @change="fetchList" />
      <el-select v-model="query.status" placeholder="全部状态" clearable class="filter-item">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-button type="primary" @click="fetchList">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
      <div class="spacer" />
      <el-button v-if="isAdmin" type="warning" :icon="Switch" :disabled="!selectedTasks.length" @click="openShift">
        平移选中（{{ selectedTasks.length }}）
      </el-button>
      <el-button v-if="isAdmin" type="danger" :icon="CircleClose" @click="openStockout">缺货取消</el-button>
      <el-button type="success" :icon="Refresh" @click="openGenerate">生成配送任务</el-button>
    </div>

    <!-- 表格 -->
    <el-table v-loading="loading" :data="tableData" stripe @selection-change="onSelectionChange">
      <el-table-column v-if="isAdmin" type="selection" width="46" />
      <el-table-column prop="taskNo" label="任务编号" min-width="180" />
      <el-table-column prop="deliveryDate" label="配送日期" width="120" />
      <el-table-column prop="className" label="班级" width="110" />
      <el-table-column prop="studentName" label="学生" width="90" />
      <el-table-column prop="productName" label="奶品" min-width="110" />
      <el-table-column prop="spec" label="规格" width="90" />
      <el-table-column prop="quantity" label="数量" width="70" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="taskStatusTag(row.status)" size="small">{{ taskStatusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1 && isAdmin" link type="primary" @click="handleStart(row)">开始配送</el-button>
          <el-button v-if="row.status === 1 && isAdmin" link type="warning" @click="openRebalance(row)">期末摊平</el-button>
          <el-button v-if="row.status === 1 || row.status === 2" link type="danger" @click="handleCancel(row)">取消</el-button>
          <el-button link type="info" @click="viewRecords(row)">查看记录</el-button>
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

    <!-- 缺货批量取消对话框 -->
    <el-dialog v-model="stockoutVisible" title="配送前缺货批量取消" width="480px">
      <el-alert type="warning" :closable="false"
        title="仅取消所选日期+奶品的「待配送」任务；已完成/配送中任务不受影响；零散订单当日配额按台账自动回补。" />
      <el-form label-width="80px" style="margin-top: 14px">
        <el-form-item label="配送日期" required>
          <el-date-picker v-model="stockoutForm.deliveryDate" type="date" placeholder="选择日期"
            value-format="YYYY-MM-DD" class="full-width" />
        </el-form-item>
        <el-form-item label="奶品" required>
          <el-select v-model="stockoutForm.productId" placeholder="选择奶品" filterable class="full-width">
            <el-option v-for="p in productOptions" :key="p.id" :label="`${p.productName}（${p.spec || ''}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="stockoutForm.reason" placeholder="选填，如：供应商断供" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stockoutVisible = false">取消</el-button>
        <el-button type="danger" :loading="stockouting" @click="handleStockout">确认取消</el-button>
      </template>
    </el-dialog>

    <!-- 生成任务对话框 -->
    <el-dialog v-model="generateVisible" title="生成配送任务" width="420px">
      <el-form label-width="80px">
        <el-form-item label="配送日期" required>
          <el-date-picker v-model="generateDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" class="full-width" />
        </el-form-item>
        <el-form-item label="班级">
          <GradeClassFilter v-model="generateClassId" class="full-width" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="handleGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 配送日平移动画框 -->
    <el-dialog v-model="shiftVisible" title="配送日平移" width="500px">
      <el-alert type="info" :closable="false"
        title="把选中的「待配送」任务平移到目标日：目标日已有同订单同品种任务则合并数量，否则新建。选中任务已在配送中、或目标日任务已开始配送时，将拒绝整批。" />
      <el-form label-width="90px" style="margin-top: 14px">
        <el-form-item label="选中任务">{{ selectedTasks.length }} 条</el-form-item>
        <el-form-item label="目标日期" required>
          <el-date-picker v-model="shiftTargetDate" type="date" placeholder="选择目标配送日"
            value-format="YYYY-MM-DD" class="full-width" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shiftVisible = false">取消</el-button>
        <el-button type="warning" :loading="shifting" @click="handleShift">确认平移</el-button>
      </template>
    </el-dialog>

    <!-- 期末摊平对话框 -->
    <el-dialog v-model="rebalanceVisible" title="学期末摊平" width="500px">
      <el-alert type="info" :closable="false"
        title="把该订单从今天起的剩余盒数，重排到截止日当天及之前的待配送任务上（每天最多 2 盒）；截止日之后的待配送任务将作废。可重复执行。" />
      <el-form label-width="90px" style="margin-top: 14px">
        <el-form-item label="订单">{{ rebalanceRow?.orderNo || rebalanceRow?.orderId }}</el-form-item>
        <el-form-item label="截止日期" required>
          <el-date-picker v-model="rebalanceDeadline" type="date" placeholder="在此日期前送完"
            value-format="YYYY-MM-DD" class="full-width" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rebalanceVisible = false">取消</el-button>
        <el-button type="primary" :loading="rebalancing" @click="handleRebalance">确认摊平</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Refresh, CircleClose, Switch } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  getDeliveryTaskList, generateDeliveryTasks, startDeliveryTask, cancelDeliveryTask,
  stockoutCancelTasks, shiftDeliveryTasks, rebalanceDeliveryQuantities
} from '@/api/delivery'
import { getProductList } from '@/api/product'
import GradeClassFilter from '@/views/clazz/components/GradeClassFilter.vue'

// 开始配送由管理员/配送站执行（配送任务开始配送会联动订单并触发退款闸门），班主任只读+签收
const userStore = useUserStore()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const emit = defineEmits<{ (e: 'view-records', task: any): void }>()

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const queryDate = ref('')
const generateVisible = ref(false)
const generateDate = ref('')
const generateClassId = ref<number | undefined>(undefined)
const generating = ref(false)
const selectedTasks = ref<any[]>([])

const query = reactive({
  pageNum: 1, pageSize: 10,
  classId: undefined as number | undefined,
  status: undefined as number | undefined
})

const statusOptions = [
  { value: 1, label: '待配送' },
  { value: 2, label: '配送中' },
  { value: 3, label: '已完成' },
  { value: 4, label: '已取消' }
]
const taskStatusText = (s: number) => statusOptions.find((x) => x.value === s)?.label ?? '未知'
const taskStatusTag = (s: number): any => {
  const map: Record<number, string> = { 1: 'warning', 2: 'primary', 3: 'success', 4: 'danger' }
  return map[s] ?? 'info'
}

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getDeliveryTaskList({
      ...query,
      deliveryDate: queryDate.value || undefined
    })
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

function handleReset() {
  queryDate.value = ''
  query.classId = undefined
  query.status = undefined
  query.pageNum = 1
  fetchList()
}

function onSelectionChange(rows: any[]) {
  selectedTasks.value = rows
}

function openGenerate() {
  generateDate.value = ''
  generateClassId.value = undefined
  generateVisible.value = true
}

async function handleGenerate() {
  if (!generateDate.value) {
    ElMessage.warning('请选择配送日期')
    return
  }
  generating.value = true
  try {
    const res: any = await generateDeliveryTasks(generateDate.value, generateClassId.value)
    ElMessage.success(`已生成 ${res.data} 条配送任务`)
    generateVisible.value = false
    fetchList()
  } finally {
    generating.value = false
  }
}

async function handleStart(row: any) {
  await startDeliveryTask(row.id)
  ElMessage.success('已开始配送')
  fetchList()
}

async function handleCancel(row: any) {
  const { value: reason } = await ElMessageBox.prompt('请输入取消原因（可选）', '取消确认', {
    type: 'warning', inputPlaceholder: '可留空'
  }).catch(() => ({ value: undefined as any }))
  if (reason === undefined) return
  await cancelDeliveryTask(row.id, reason || undefined)
  ElMessage.success('已取消')
  fetchList()
}

function viewRecords(row: any) {
  emit('view-records', row)
}

// ==================== 配送日平移 ====================
const shiftVisible = ref(false)
const shiftTargetDate = ref('')
const shifting = ref(false)

function openShift() {
  if (!selectedTasks.value.length) {
    ElMessage.warning('请先勾选要平移的任务')
    return
  }
  shiftTargetDate.value = ''
  shiftVisible.value = true
}

async function handleShift() {
  if (!shiftTargetDate.value) {
    ElMessage.warning('请选择目标日期')
    return
  }
  shifting.value = true
  try {
    const res: any = await shiftDeliveryTasks({
      taskIds: selectedTasks.value.map((t) => t.id),
      targetDate: shiftTargetDate.value
    })
    const d = res.data || {}
    const parts = [
      `成功平移 ${d.shifted ?? 0} 条（合并 ${d.merged ?? 0} / 新建 ${d.created ?? 0}）`,
      `跳过 ${d.skipped ?? 0} 条`
    ]
    if (d.messages && d.messages.length) parts.push(d.messages.join('；'))
    ElMessage.success(parts.join('，'))
    shiftVisible.value = false
    selectedTasks.value = []
    fetchList()
  } finally {
    shifting.value = false
  }
}

// ==================== 学期末摊平 ====================
const rebalanceVisible = ref(false)
const rebalanceRow = ref<any>(null)
const rebalanceDeadline = ref('')
const rebalancing = ref(false)

function openRebalance(row: any) {
  rebalanceRow.value = row
  rebalanceDeadline.value = ''
  rebalanceVisible.value = true
}

async function handleRebalance() {
  if (!rebalanceRow.value) return
  if (!rebalanceDeadline.value) {
    ElMessage.warning('请选择截止日期')
    return
  }
  rebalancing.value = true
  try {
    const res: any = await rebalanceDeliveryQuantities({
      orderId: rebalanceRow.value.orderId,
      deadline: rebalanceDeadline.value
    })
    ElMessage.success(`已调整 ${res.data} 条任务数量`)
    rebalanceVisible.value = false
    fetchList()
  } finally {
    rebalancing.value = false
  }
}

// ==================== 缺货批量取消 ====================
const stockoutVisible = ref(false)
const stockouting = ref(false)
const productOptions = ref<any[]>([])
const stockoutForm = reactive({
  deliveryDate: '',
  productId: undefined as number | undefined,
  reason: ''
})

function openStockout() {
  stockoutForm.deliveryDate = queryDate.value || ''
  stockoutForm.productId = undefined
  stockoutForm.reason = ''
  if (!productOptions.value.length) {
    getProductList({ pageNum: 1, pageSize: 999 }).then((res: any) => {
      productOptions.value = (res.data && res.data.list) || res.data || []
    }).catch(() => {})
  }
  stockoutVisible.value = true
}

async function handleStockout() {
  if (!stockoutForm.deliveryDate) {
    ElMessage.warning('请选择配送日期')
    return
  }
  if (!stockoutForm.productId) {
    ElMessage.warning('请选择奶品')
    return
  }
  stockouting.value = true
  try {
    const res: any = await stockoutCancelTasks({
      deliveryDate: stockoutForm.deliveryDate,
      productId: stockoutForm.productId,
      reason: stockoutForm.reason || undefined
    })
    ElMessage.success(`已取消 ${res.data} 条待配送任务`)
    stockoutVisible.value = false
    fetchList()
  } finally {
    stockouting.value = false
  }
}

onMounted(() => {
  fetchList()
})
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
  .filter-item { width: 160px; }
  .spacer { flex: 1; }
}
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
.full-width { width: 100%; }
</style>
