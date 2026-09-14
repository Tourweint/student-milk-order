<template>
  <div class="subscription-manage">
    <!-- 筛选 -->
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="filter-item" @change="handleSearch">
        <el-option label="已开启" :value="1" />
        <el-option label="已暂停" :value="2" />
        <el-option label="已关闭" :value="0" />
      </el-select>
      <el-button type="primary" @click="fetchList">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">开启自动续订</el-button>
    </div>

    <!-- 表格 -->
    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="studentName" label="学生" width="100" />
      <el-table-column prop="packageName" label="套餐" min-width="130" />
      <el-table-column prop="originalOrderNo" label="原订单号" min-width="180" />
      <el-table-column label="周期" width="100">
        <template #default="{ row }">{{ row.cycleTypeText }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'warning' : 'info'" size="small">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="nextRenewalTime" label="下次续订" min-width="160" />
      <el-table-column prop="lastRenewalTime" label="上次续订" min-width="160">
        <template #default="{ row }">{{ row.lastRenewalTime || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" link type="warning" @click="handleTrigger(row)">手动续订</el-button>
          <el-button v-if="row.status === 1" link type="primary" @click="handlePause(row)">暂停</el-button>
          <el-button v-if="row.status === 2" link type="success" @click="handleResume(row)">恢复</el-button>
          <el-button v-if="row.status === 1 || row.status === 2" link type="danger" @click="handleClose(row)">关闭</el-button>
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

    <!-- 开启续订对话框 -->
    <el-dialog v-model="createVisible" title="开启自动续订" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="学生" prop="studentId">
          <el-select v-model="form.studentId" placeholder="选择学生" filterable class="full-width" @change="onStudentChange">
            <el-option-group v-for="g in groupedStudents" :key="g.className" :label="g.className">
              <el-option v-for="s in g.students" :key="s.id" :label="`${s.studentName}（${s.studentNo}）`" :value="s.id" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="原订单" prop="originalOrderId">
          <el-select v-model="form.originalOrderId" placeholder="选择已支付订单" filterable class="full-width" :disabled="!form.studentId">
            <el-option v-for="o in paidOrders" :key="o.id"
              :label="`${o.orderNo}（¥${o.payAmount}，${o.deliveryStartDate}~${o.deliveryEndDate}）`" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="套餐" prop="packageId">
          <el-select v-model="form.packageId" placeholder="选择套餐" class="full-width">
            <el-option v-for="p in packageList" :key="p.id" :label="`${p.packageName}（¥${p.discountPrice}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="续订周期">
          <el-radio-group v-model="form.cycleType">
            <el-radio :value="1">每月续订</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">开启</el-button>
      </template>
    </el-dialog>

    <!-- 暂停续订对话框 -->
    <el-dialog v-model="pauseVisible" title="暂停自动续订" width="460px">
      <el-alert type="info" :closable="false"
        title="暂停期间不再自动续订；已生成的配送任务默认照常送完（已支付权益）。" />
      <el-form label-width="90px" style="margin-top: 14px">
        <el-form-item label="暂停原因">
          <el-input v-model="pauseForm.reason" placeholder="选填，如：放假暂停" />
        </el-form-item>
        <el-form-item label="未配送任务">
          <el-radio-group v-model="pauseForm.keepPendingTasks">
            <el-radio :value="true">保留照常配送</el-radio>
            <el-radio :value="false">同时取消（该期不送）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pauseVisible = false">取消</el-button>
        <el-button type="warning" :loading="pausing" @click="submitPause">确认暂停</el-button>
      </template>
    </el-dialog>

    <!-- 关闭续订对话框（终止时机二选一） -->
    <el-dialog v-model="closeVisible" title="关闭自动续订（终止订阅）" width="500px">
      <el-form label-width="90px">
        <el-form-item label="终止时机">
          <el-radio-group v-model="closeForm.terminateNow">
            <div style="display: flex; flex-direction: column; gap: 8px">
              <el-radio :value="false">送完当前周期：已生成的配送任务照常执行，仅停止后续续订</el-radio>
              <el-radio :value="true">立即终止：同时取消当前周期未配送任务（剩余期次线下退款）</el-radio>
            </div>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="closeForm.reason" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeVisible = false">取消</el-button>
        <el-button type="danger" :loading="closing" @click="submitClose">确认关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  getSubscriptionList, createSubscription, deleteSubscription, triggerSubscription,
  pauseSubscription, resumeSubscription
} from '@/api/subscription'
import { getStudentList } from '@/api/student'
import { getOrderList } from '@/api/order'
import { getPackageList } from '@/api/product'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const studentList = ref<any[]>([])
const paidOrders = ref<any[]>([])
const packageList = ref<any[]>([])
const createVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()

const query = reactive({
  pageNum: 1, pageSize: 10,
  status: undefined as number | undefined
})

/** 学生按班级分组展示，长列表下更容易定位 */
const groupedStudents = computed(() => {
  const groups: { className: string; students: any[] }[] = []
  studentList.value.forEach((s) => {
    const className = s.className || '未分班'
    let g = groups.find((x) => x.className === className)
    if (!g) {
      g = { className, students: [] }
      groups.push(g)
    }
    g.students.push(s)
  })
  return groups
})

const form = reactive({
  studentId: undefined as number | undefined,
  originalOrderId: undefined as number | undefined,
  packageId: undefined as number | undefined,
  cycleType: 1,
  remark: ''
})

const rules: FormRules = {
  studentId: [{ required: true, message: '请选择学生', trigger: 'change' }],
  originalOrderId: [{ required: true, message: '请选择原订单', trigger: 'change' }],
  packageId: [{ required: true, message: '请选择套餐', trigger: 'change' }]
}

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getSubscriptionList(query)
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

async function fetchStudents() {
  const res: any = await getStudentList({ pageNum: 1, pageSize: 999 })
  studentList.value = res.data.list || []
}

async function fetchPackages() {
  const res: any = await getPackageList()
  packageList.value = res.data || []
}

async function onStudentChange() {
  form.originalOrderId = undefined
  if (!form.studentId) return
  const res: any = await getOrderList({ pageNum: 1, pageSize: 999, studentId: form.studentId, status: 2 })
  paidOrders.value = res.data.list || []
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.status = undefined
  query.pageNum = 1
  fetchList()
}

function openCreate() {
  form.studentId = undefined
  form.originalOrderId = undefined
  form.packageId = undefined
  form.cycleType = 1
  form.remark = ''
  paidOrders.value = []
  createVisible.value = true
}

async function handleCreate() {
  if (!formRef.value) return
  await formRef.value.validate()
  creating.value = true
  try {
    await createSubscription({ ...form })
    ElMessage.success('已开启自动续订')
    createVisible.value = false
    fetchList()
  } finally {
    creating.value = false
  }
}

async function handleTrigger(row: any) {
  await ElMessageBox.confirm(`确定立即为「${row.studentName}」执行续订吗？将生成新订单并自动支付。`, '手动续订', {
    type: 'warning', confirmButtonText: '确定续订', cancelButtonText: '取消'
  })
  const res: any = await triggerSubscription(row.id)
  ElMessage.success(`续订成功，新订单ID：${res.data}`)
  fetchList()
}

/** 暂停：默认保留未配送任务，对话框内可选同时取消 */
const pauseVisible = ref(false)
const pausing = ref(false)
const pauseTarget = ref<any>(null)
const pauseForm = reactive({ reason: '', keepPendingTasks: true })

function handlePause(row: any) {
  pauseTarget.value = row
  pauseForm.reason = ''
  pauseForm.keepPendingTasks = true
  pauseVisible.value = true
}

async function submitPause() {
  if (!pauseTarget.value) return
  pausing.value = true
  try {
    await pauseSubscription(pauseTarget.value.id, pauseForm.reason || undefined, pauseForm.keepPendingTasks)
    ElMessage.success('已暂停，期间不自动续订')
    pauseVisible.value = false
    fetchList()
  } finally {
    pausing.value = false
  }
}

async function handleResume(row: any) {
  await ElMessageBox.confirm(
    `恢复后将从下一个续订时间点继续自动续订（时间已顺延），确定恢复「${row.studentName}」的续订吗？`,
    '恢复续订', { type: 'warning' })
  await resumeSubscription(row.id)
  ElMessage.success('已恢复')
  fetchList()
}

/** 关闭：对话框内选择终止时机（送完当前周期 / 立即取消未配送任务） */
const closeVisible = ref(false)
const closing = ref(false)
const closeTarget = ref<any>(null)
const closeForm = reactive({ terminateNow: false, reason: '' })

function handleClose(row: any) {
  closeTarget.value = row
  closeForm.terminateNow = false
  closeForm.reason = ''
  closeVisible.value = true
}

async function submitClose() {
  if (!closeTarget.value) return
  closing.value = true
  try {
    await deleteSubscription(closeTarget.value.id, closeForm.terminateNow, closeForm.reason || undefined)
    ElMessage.success(closeForm.terminateNow ? '已关闭并取消未配送任务' : '已关闭，当前周期将执行完毕')
    closeVisible.value = false
    fetchList()
  } finally {
    closing.value = false
  }
}

onMounted(() => {
  fetchStudents()
  fetchPackages()
  fetchList()
})
</script>

<style scoped lang="scss">
.subscription-manage { padding: 20px; }
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
